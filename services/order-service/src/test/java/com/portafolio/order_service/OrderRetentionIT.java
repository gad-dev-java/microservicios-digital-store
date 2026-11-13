package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.portafolio.order_service.application.ports.out.CatalogPort;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.infrastructure.adapters.out.IdempotencyPurgeBatch;
import com.portafolio.order_service.infrastructure.adapters.out.OrderRetentionBatch;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(
        properties = {
            "MERCADOPAGO_APPLICATION_ID=test-app",
            "MERCADOPAGO_EXPECTED_LIVE_MODE=false",
            "outbox.relay.enabled=false"
        })
class OrderRetentionIT {
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
    IdempotencyPurgeBatch idempotency;

    @Autowired
    OrderRetentionBatch retention;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    PaymentGatewayPort paymentGateway;

    @MockitoBean
    CatalogPort catalog;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM entitlements");
        jdbc.update("DELETE FROM idempotency_requests");
        jdbc.update("DELETE FROM webhook_receipts");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void idempotencyDeletesExpiredRequestsOneBatchAtATime() {
        UUID orderId = seedOrder();
        UUID expiredA = seedIdempotency(orderId, -2);
        UUID expiredB = seedIdempotency(orderId, -1);
        UUID current = seedIdempotency(orderId, 1);

        assertEquals(1, idempotency.deleteExpired(1));
        assertEquals(1, count("SELECT count(*) FROM idempotency_requests WHERE expires_at < now()"));
        assertEquals(1, idempotency.deleteExpired(1));
        assertEquals(0, idempotency.deleteExpired(1));

        assertEquals(0, rows("idempotency_requests", expiredA));
        assertEquals(0, rows("idempotency_requests", expiredB));
        assertEquals(1, rows("idempotency_requests", current));
    }

    @Test
    void webhookPurgeKeepsRecentPendingAndFailedRows() {
        UUID oldA = seedWebhook("PROCESSED", -35);
        UUID oldB = seedWebhook("PROCESSED", -31);
        UUID recent = seedWebhook("PROCESSED", -29);
        UUID withoutDate = seedWebhook("PROCESSED", null);
        UUID received = seedWebhook("RECEIVED", -40);
        UUID failed = seedWebhook("FAILED", -40);

        assertEquals(1, retention.deleteProcessedWebhooks(1));

        // La transacción exterior se revierte; REQUIRES_NEW debe conservar la purga.
        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            assertEquals(1, retention.deleteProcessedWebhooks(1));
            outer.setRollbackOnly();
        });

        assertEquals(0, retention.deleteProcessedWebhooks(1));
        assertEquals(0, rows("webhook_receipts", oldA));
        assertEquals(0, rows("webhook_receipts", oldB));
        assertEquals(1, rows("webhook_receipts", recent));
        assertEquals(1, rows("webhook_receipts", withoutDate));
        assertEquals(1, rows("webhook_receipts", received));
        assertEquals(1, rows("webhook_receipts", failed));
    }

    @Test
    void outboxPurgeKeepsRecentPendingFailedInflightAndDeadRows() {
        UUID oldA = seedOutbox("PUBLISHED", -35);
        UUID oldB = seedOutbox("PUBLISHED", -31);
        UUID recent = seedOutbox("PUBLISHED", -29);
        UUID withoutDate = seedOutbox("PUBLISHED", null);
        UUID pending = seedOutbox("PENDING", -40);
        UUID failed = seedOutbox("FAILED", -40);
        UUID inFlight = seedOutbox("IN_FLIGHT", -40);
        UUID dead = seedOutbox("DEAD", -40);

        assertEquals(1, retention.deletePublishedOutbox(1));
        assertEquals(1, retention.deletePublishedOutbox(1));
        assertEquals(0, retention.deletePublishedOutbox(1));

        assertEquals(0, rows("outbox_events", oldA));
        assertEquals(0, rows("outbox_events", oldB));
        assertEquals(1, rows("outbox_events", recent));
        assertEquals(1, rows("outbox_events", withoutDate));
        assertEquals(1, rows("outbox_events", pending));
        assertEquals(1, rows("outbox_events", failed));
        assertEquals(1, rows("outbox_events", inFlight));
        assertEquals(1, rows("outbox_events", dead));
    }

    private UUID seedOrder() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders
                    (id, buyer_sub, buyer_email, total_amount,
                     currency, status, paid_at)
                VALUES (?, 'buyer', 'buyer@example.test',
                        12.00, 'PEN', 'PAID', now())
                """, id);
        return id;
    }

    private UUID seedIdempotency(UUID orderId, int expiresInDays) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO idempotency_requests
                    (id, actor_sub, operation, idempotency_key,
                     request_hash, order_id, created_at, expires_at)
                VALUES (?, 'buyer', 'CREATE_ORDER', ?, ?, ?, ?, ?)
                """, id, id.toString(), "a".repeat(64), orderId, timestamp(-40), timestamp(expiresInDays));
        return id;
    }

    private UUID seedWebhook(String status, Integer processedInDays) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO webhook_receipts
                    (id, provider_notification_id, payload,
                     status, processed_at)
                VALUES (?, ?, '{}'::jsonb, ?, ?)
                """, id, id.toString(), status, timestamp(processedInDays));
        return id;
    }

    private UUID seedOutbox(String status, Integer publishedInDays) {
        UUID id = UUID.randomUUID();
        UUID orderId = seedOrder();
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, order_id, event_type, payload,
                     status, created_at, published_at)
                VALUES (?, ?, 'ORDER_PAID', '{}'::jsonb, ?, ?, ?)
                """, id, orderId, status, timestamp(-40), timestamp(publishedInDays));
        return id;
    }

    private int rows(String table, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static Timestamp timestamp(Integer daysFromNow) {
        return daysFromNow == null ? null : Timestamp.from(Instant.now().plus(daysFromNow, ChronoUnit.DAYS));
    }
}
