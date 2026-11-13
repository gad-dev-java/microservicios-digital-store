package com.portafolio.notification_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.portafolio.notification_service.application.service.OrderPaidEmailService;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureTracing
@ActiveProfiles("test")
@Testcontainers
@Import(NotificationFlowIT.Topics.class)
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=true", "app.notifications.sender.enabled=false"})
class NotificationFlowIT {
    private static final String TOPIC = "orders.events.v1";
    private static final String DLT = "orders.events.v1.DLT";
    private static final String GROUP = "notification-service-v1";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");

    @Container
    static final GenericContainer<?> mailpit = new GenericContainer<>("axllent/mailpit:v1.33.0")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.mail.host", mailpit::getHost);
        registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic orderEventsTopic() {
            return new NewTopic(TOPIC, 3, (short) 1);
        }

        @Bean
        NewTopic orderEventsDltTopic() {
            return new NewTopic(DLT, 3, (short) 1);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, Object> publisher;

    @Autowired
    OrderPaidEmailService emailService;

    @Autowired
    ObjectMapper json;

    @Autowired
    Tracer tracer;

    @Autowired
    ObservationRegistry observationRegistry;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM email_deliveries");
        publisher.setObservationRegistry(observationRegistry);
        publisher.setObservationEnabled(true);
    }

    @Test
    void paidEventSendsOneEmailDuplicateIsIgnoredAndInvalidEventGoesToDlt() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String payload = """
                {
                  "eventId": "%s",
                  "eventType": "ORDER_PAID",
                  "schemaVersion": 1,
                  "orderId": "%s",
                  "buyerEmail": "buyer@example.test",
                  "amount": 12.00,
                  "currency": "PEN",
                  "items": [
                    {"title": "Curso de Java"}
                  ]
                }
                """.formatted(eventId, orderId);

        var firstSend = publisher.send(TOPIC, orderId.toString(), payload).get(10, TimeUnit.SECONDS);
        await(() -> deliveryCount(eventId) == 1);

        emailService.sendNext();

        await(() -> mailpitMessages().path("total").asInt() == 1);
        assertEquals("SENT", deliveryStatus(eventId));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT attempts FROM email_deliveries WHERE source_message_id = ?", Integer.class, eventId));
        assertTrue(mailpitMessages().toString().contains("Compra confirmada"));
        assertTrue(mailpitMessages().toString().contains("buyer@example.test"));

        var duplicateSend = publisher.send(TOPIC, orderId.toString(), payload).get(10, TimeUnit.SECONDS);

        // Espera el commit del offset: así la comprobación no depende de Thread.sleep().
        await(() -> committedPast(
                duplicateSend.getRecordMetadata().partition(),
                duplicateSend.getRecordMetadata().offset()));

        assertEquals(1, deliveryCount(eventId));
        assertEquals(1, mailpitMessages().path("total").asInt());

        try (KafkaConsumer<String, String> dltConsumer = new KafkaConsumer<>(dltProperties())) {
            dltConsumer.subscribe(List.of(DLT));

            // Hasta aquí, el duplicado no produjo un mensaje en DLT.
            assertTrue(dltConsumer.poll(Duration.ofSeconds(1)).isEmpty());

            String invalidJson = "{";
            publisher.send(TOPIC, orderId.toString(), invalidJson).get(10, TimeUnit.SECONDS);

            ConsumerRecord<String, String> deadLetter = waitForDlt(dltConsumer);
            assertNotNull(deadLetter);
            assertEquals(invalidJson, deadLetter.value());
        }

        assertEquals(1, deliveryCount(eventId));
        assertEquals(1, mailpitMessages().path("total").asInt());
        assertEquals(TOPIC, firstSend.getRecordMetadata().topic());
    }

    @Test
    void kafkaListenerStoresProducerTraceWithDelivery() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String payload = """
                {
                  "eventId": "%s",
                  "eventType": "ORDER_PAID",
                  "schemaVersion": 1,
                  "orderId": "%s",
                  "buyerEmail": "buyer@example.test",
                  "amount": 12.00,
                  "currency": "PEN",
                  "items": [{"title": "Curso de Java"}]
                }
                """.formatted(eventId, orderId);

        Span producerSpan =
                tracer.spanBuilder().setNoParent().name("test.producer").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(producerSpan)) {
            publisher.send(TOPIC, orderId.toString(), payload).get(10, TimeUnit.SECONDS);
        } finally {
            producerSpan.end();
        }

        await(() -> deliveryCount(eventId) == 1);
        String storedTraceparent = jdbc.queryForObject(
                "SELECT traceparent FROM email_deliveries WHERE source_message_id = ?", String.class, eventId);
        assertNotNull(storedTraceparent);
        assertEquals(producerSpan.context().traceId(), storedTraceparent.split("-")[1]);
    }

    private int deliveryCount(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM email_deliveries WHERE source_message_id = ?", Integer.class, eventId);
    }

    private String deliveryStatus(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM email_deliveries WHERE source_message_id = ?", String.class, eventId);
    }

    private JsonNode mailpitMessages() throws Exception {
        URI uri = URI.create("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025) + "/api/v1/messages");
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        return json.readTree(body);
    }

    private boolean committedPast(int partition, long offset) {
        try (AdminClient admin =
                AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            var committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(3, TimeUnit.SECONDS)
                    .get(new TopicPartition(TOPIC, partition));
            return committed != null && committed.offset() > offset;
        } catch (Exception exception) {
            return false;
        }
    }

    private Properties dltProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-dlt-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return properties;
    }

    private ConsumerRecord<String, String> waitForDlt(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                return record;
            }
        }
        return null;
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(condition.call(), "No se cumplió la condición en 15 segundos");
    }
}
