package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.portafolio.order_service.application.ports.out.CatalogPort;
import com.portafolio.order_service.application.ports.out.OutboxEvent;
import com.portafolio.order_service.application.ports.out.OutboxStorePort;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.application.service.PublishOutboxService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(
        properties = {
            "MERCADOPAGO_APPLICATION_ID=test-app",
            "MERCADOPAGO_EXPECTED_LIVE_MODE=false",
            "outbox.relay.enabled=false"
        })
class OutboxRelayIT {
    private static final String TOPIC = "orders.events.v1";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeAll
    static void createTopic() throws Exception {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());

        try (AdminClient admin = AdminClient.create(properties)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxStorePort store;

    @Autowired
    PublishOutboxService relay;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    PaymentGatewayPort paymentGateway;

    @MockitoBean
    CatalogPort catalog;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void publishesWithOrderKeyAndSameEventIdAfterReplay() throws Exception {
        Seed seed = seedOutboxEvent();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(TOPIC));

            relay.runBatch();

            ConsumerRecord<String, String> first = awaitEvent(consumer, seed.eventId());

            assertEquals(seed.orderId().toString(), first.key());
            assertEquals(
                    seed.eventId().toString(),
                    objectMapper.readTree(first.value()).path("eventId").asText());
            assertEquals("PUBLISHED", status(seed.eventId()));
            assertNotNull(jdbc.queryForObject(
                    "SELECT published_at FROM outbox_events WHERE id = ?", Timestamp.class, seed.eventId()));

            // Simula una publicación confirmada por Kafka seguida de una
            // caída antes de guardar PUBLISHED en PostgreSQL.
            jdbc.update("""
                    UPDATE outbox_events
                    SET status = 'IN_FLIGHT',
                        lease_until = now() - INTERVAL '1 second',
                        published_at = NULL
                    WHERE id = ?
                    """, seed.eventId());

            relay.runBatch();

            ConsumerRecord<String, String> repeated = awaitEvent(consumer, seed.eventId());

            assertEquals(first.key(), repeated.key());
            assertEquals(first.value(), repeated.value());
            assertEquals("PUBLISHED", status(seed.eventId()));
            assertEquals(Integer.valueOf(2), attempts(seed.eventId()));
        }
    }

    @Test
    void twoWorkersCannotClaimTheSameRow() throws Exception {
        seedOutboxEvent();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<List<OutboxEvent>> first = workers.submit(() -> {
                start.await();
                return store.claimDue(1, 30);
            });
            Future<List<OutboxEvent>> second = workers.submit(() -> {
                start.await();
                return store.claimDue(1, 30);
            });

            start.countDown();

            int totalClaimed = first.get(10, TimeUnit.SECONDS).size()
                    + second.get(10, TimeUnit.SECONDS).size();

            assertEquals(1, totalClaimed);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedButOldWorkerCannotConfirm() {
        Seed seed = seedOutboxEvent();

        OutboxEvent firstWorker = store.claimDue(1, 30).getFirst();
        assertTrue(store.claimDue(1, 30).isEmpty());

        jdbc.update("""
                UPDATE outbox_events
                SET lease_until = now() - INTERVAL '1 second'
                WHERE id = ?
                """, seed.eventId());

        OutboxEvent secondWorker = store.claimDue(1, 30).getFirst();

        assertEquals(seed.eventId(), secondWorker.id());
        assertEquals(2, secondWorker.attempts());
        assertFalse(store.markPublished(firstWorker));
        assertTrue(store.markPublished(secondWorker));
        assertEquals("PUBLISHED", status(seed.eventId()));
    }

    @Test
    void failureRetriesAndThenLeavesDeadRowForReview() {
        Seed seed = seedOutboxEvent();

        PublishOutboxService failingRelay = new PublishOutboxService(
                store,
                event -> {
                    throw new IllegalStateException("BROKER_DOWN");
                },
                2, // maxAttempts
                1, // batchSize
                30 // leaseSeconds
                );

        failingRelay.runBatch();

        assertEquals("FAILED", status(seed.eventId()));
        assertEquals(Integer.valueOf(1), attempts(seed.eventId()));
        assertEquals(
                "IllegalStateException",
                jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE id = ?", String.class, seed.eventId()));

        Timestamp nextAttempt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM outbox_events WHERE id = ?", Timestamp.class, seed.eventId());
        assertNotNull(nextAttempt);
        assertTrue(nextAttempt.toInstant().isAfter(Instant.now()));

        // Adelanta el reloj de reintento sin esperar diez segundos.
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() WHERE id = ?", seed.eventId());

        failingRelay.runBatch();

        assertEquals("DEAD", status(seed.eventId()));
        assertEquals(Integer.valueOf(2), attempts(seed.eventId()));
        assertTrue(store.claimDue(1, 30).isEmpty());
    }

    private Seed seedOutboxEvent() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO orders
                    (id, buyer_sub, buyer_email, total_amount,
                     currency, status, paid_at)
                VALUES (?, 'buyer-test', 'buyer@example.test',
                        12.00, 'PEN', 'PAID', now())
                """, orderId);

        String payload = """
                {
                  "eventId": "%s",
                  "eventType": "ORDER_PAID",
                  "schemaVersion": 1,
                  "orderId": "%s",
                  "buyerEmail": "buyer@example.test",
                  "amount": 12.00,
                  "currency": "PEN",
                  "items": []
                }
                """.formatted(eventId, orderId);

        jdbc.update("""
                INSERT INTO outbox_events
                    (id, order_id, event_type, payload)
                VALUES (?, ?, 'ORDER_PAID', ?::jsonb)
                """, eventId, orderId, payload);

        return new Seed(orderId, eventId);
    }

    private KafkaConsumer<String, String> consumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        return new KafkaConsumer<>(properties);
    }

    private ConsumerRecord<String, String> awaitEvent(KafkaConsumer<String, String> consumer, UUID eventId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();

        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                Header header = record.headers().lastHeader("eventId");
                if (header != null && eventId.toString().equals(new String(header.value(), StandardCharsets.UTF_8))) {
                    return record;
                }
            }
        }

        throw new AssertionError("Kafka no recibió eventId=" + eventId);
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private Integer attempts(UUID eventId) {
        return jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, eventId);
    }

    private record Seed(UUID orderId, UUID eventId) {}
}
