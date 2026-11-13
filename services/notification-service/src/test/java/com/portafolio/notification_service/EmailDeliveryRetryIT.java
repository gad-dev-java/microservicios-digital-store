package com.portafolio.notification_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.portafolio.notification_service.application.ports.in.HandleOrderPaidUseCase;
import com.portafolio.notification_service.application.ports.out.DeliveryRepositoryPort;
import com.portafolio.notification_service.application.ports.out.MailDeliveryException;
import com.portafolio.notification_service.application.ports.out.MailSenderPort;
import com.portafolio.notification_service.application.service.OrderPaidEmailService;
import com.portafolio.notification_service.domain.models.EmailDelivery;
import com.portafolio.notification_service.domain.models.OrderPaidEvent;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureTracing
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=false", "app.notifications.sender.enabled=false"})
class EmailDeliveryRetryIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HandleOrderPaidUseCase useCase;

    @Autowired
    OrderPaidEmailService emailService;

    @Autowired
    DeliveryRepositoryPort deliveries;

    @Autowired
    Tracer tracer;

    @MockitoBean
    MailSenderPort mailSender;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM email_deliveries");
        reset(mailSender);
    }

    @Test
    void scheduledEmailRestoresStoredTrace() {
        OrderPaidEvent original = event();
        String traceId = "abcdef1234567890abcdef1234567890";
        String traceparent = "00-" + traceId + "-1234567890abcdef-01";
        OrderPaidEvent event = new OrderPaidEvent(
                original.eventId(),
                original.orderId(),
                original.buyerEmail(),
                original.amount(),
                original.currency(),
                original.titles(),
                traceparent,
                null);
        useCase.handle(event);

        AtomicReference<String> observedTraceId = new AtomicReference<>();
        doAnswer(invocation -> {
                    observedTraceId.set(tracer.currentSpan().context().traceId());
                    return null;
                })
                .when(mailSender)
                .send(any(EmailDelivery.class));

        emailService.sendNext();

        assertEquals(traceId, observedTraceId.get());
        assertEquals("SENT", status(event.eventId()));
    }

    @Test
    void smtpFailureIsRetriedAndThenSent() {
        OrderPaidEvent event = event();
        useCase.handle(event);

        doThrow(new MailDeliveryException(new IllegalStateException("SMTP unavailable")))
                .when(mailSender)
                .send(any(EmailDelivery.class));

        emailService.sendNext();

        assertEquals("FAILED", status(event.eventId()));
        assertEquals(1, attempts(event.eventId()));
        assertTrue(jdbc.queryForObject("""
                SELECT next_attempt_at > now()
                FROM email_deliveries
                WHERE source_message_id = ?
                """, Boolean.class, event.eventId()));

        // Evita esperar el backoff real de dos segundos.
        jdbc.update("""
                UPDATE email_deliveries
                SET next_attempt_at = now()
                WHERE source_message_id = ?
                """, event.eventId());
        reset(mailSender);

        emailService.sendNext();

        assertEquals("SENT", status(event.eventId()));
        assertEquals(2, attempts(event.eventId()));
        assertTrue(jdbc.queryForObject("""
                SELECT sent_at IS NOT NULL
                FROM email_deliveries
                WHERE source_message_id = ?
                """, Boolean.class, event.eventId()));
        verify(mailSender, times(1)).send(any(EmailDelivery.class));
    }

    @Test
    void fiveSmtpFailuresMarkDeliveryDead() {
        OrderPaidEvent event = event();
        useCase.handle(event);

        doThrow(new MailDeliveryException(new IllegalStateException("SMTP unavailable")))
                .when(mailSender)
                .send(any(EmailDelivery.class));

        for (int attempt = 1; attempt <= 5; attempt++) {
            if (attempt > 1) {
                jdbc.update("""
                        UPDATE email_deliveries
                        SET next_attempt_at = now()
                        WHERE source_message_id = ?
                        """, event.eventId());
            }
            emailService.sendNext();
            assertEquals(attempt, attempts(event.eventId()));
        }

        assertEquals("DEAD", status(event.eventId()));
        emailService.sendNext();
        verify(mailSender, times(5)).send(any(EmailDelivery.class));
    }

    @Test
    void twoWorkersCannotClaimTheSameDelivery() throws Exception {
        OrderPaidEvent event = event();
        useCase.handle(event);

        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                start.await();
                return deliveries.claimNext();
            });
            var second = workers.submit(() -> {
                start.await();
                return deliveries.claimNext();
            });

            start.countDown();

            Optional<EmailDelivery> firstResult = first.get(10, TimeUnit.SECONDS);
            Optional<EmailDelivery> secondResult = second.get(10, TimeUnit.SECONDS);

            assertTrue(firstResult.isPresent() ^ secondResult.isPresent());
            assertEquals("IN_FLIGHT", status(event.eventId()));
            assertEquals(1, attempts(event.eventId()));
        }
    }

    @Test
    void expiredLeaseCanBeReclaimedAndFinalExpiredLeaseBecomesDead() {
        OrderPaidEvent event = event();
        useCase.handle(event);

        assertTrue(deliveries.claimNext().isPresent());
        jdbc.update("""
                UPDATE email_deliveries
                SET lease_until = now() - interval '1 second'
                WHERE source_message_id = ?
                """, event.eventId());

        assertTrue(deliveries.claimNext().isPresent());
        assertEquals(2, attempts(event.eventId()));

        jdbc.update("""
                UPDATE email_deliveries
                SET attempts = 5,
                    lease_until = now() - interval '1 second'
                WHERE source_message_id = ?
                """, event.eventId());

        assertFalse(deliveries.claimNext().isPresent());
        assertEquals("DEAD", status(event.eventId()));
    }

    @Test
    void differentEventIdsForSameOrderCreateOnlyOneDelivery() {
        OrderPaidEvent first = event();
        OrderPaidEvent second = new OrderPaidEvent(
                UUID.randomUUID(),
                first.orderId(),
                first.buyerEmail(),
                first.amount(),
                first.currency(),
                first.titles(),
                null,
                null);

        useCase.handle(first);
        useCase.handle(second);

        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM email_deliveries WHERE order_id = ?", Integer.class, first.orderId()));
    }

    private static OrderPaidEvent event() {
        return new OrderPaidEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "buyer@example.test",
                new BigDecimal("12.00"),
                "PEN",
                List.of("Curso de Java"),
                null,
                null);
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM email_deliveries WHERE source_message_id = ?", String.class, eventId);
    }

    private int attempts(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT attempts FROM email_deliveries WHERE source_message_id = ?", Integer.class, eventId);
    }
}
