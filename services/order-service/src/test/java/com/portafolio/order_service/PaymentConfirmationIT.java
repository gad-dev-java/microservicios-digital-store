package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.portafolio.order_service.application.ports.in.ProcessWebhookUseCase;
import com.portafolio.order_service.application.ports.out.CatalogPort;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.application.ports.out.ProviderOrderSnapshot;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {"MERCADOPAGO_APPLICATION_ID=test-app", "MERCADOPAGO_EXPECTED_LIVE_MODE=false"})
class PaymentConfirmationIT {
    private static final String BUYER = "payment-buyer";
    private static final BigDecimal UNIT_PRICE = new BigDecimal("39.90");

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
    ProcessWebhookUseCase webhook;

    @MockitoBean
    PaymentGatewayPort paymentGateway;

    @MockitoBean
    CatalogPort catalog;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        reset(paymentGateway, catalog);
        jdbc.update("DELETE FROM webhook_receipts");
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM entitlements");
        jdbc.update("DELETE FROM idempotency_requests");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void validPaymentPaysOrderAndGrantsEveryProduct() {
        Seed seed = seedOrder(BUYER, "PENDING_PAYMENT", "PENDING", UUID.randomUUID(), UUID.randomUUID());
        stubProvider(seed, validSnapshot(seed));

        notifyProvider(seed, false);

        assertTables(seed, "PAID", true, "APPROVED", null, 2, 1);
    }

    @Test
    void concurrentWebhooksPayOnlyOnce() throws Exception {
        Seed seed = seedOrder(BUYER, "PENDING_PAYMENT", "PENDING", UUID.randomUUID());
        stubProvider(seed, validSnapshot(seed));

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                start.await();
                notifyProvider(seed, false);
                return null;
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                notifyProvider(seed, false);
                return null;
            });

            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertTables(seed, "PAID", true, "APPROVED", null, 1, 1);
        assertEquals(
                2,
                count(
                        "SELECT count(*) FROM webhook_receipts "
                                + "WHERE provider_order_id = ? AND status = 'PROCESSED'",
                        seed.providerOrderId()));
    }

    @ParameterizedTest(name = "Discrepancia: {0}")
    @ValueSource(
            strings = {"AMOUNT", "CURRENCY", "REFERENCE", "APPLICATION", "NOTIFICATION_LIVE_MODE", "PROVIDER_LIVE_MODE"
            })
    void mismatchedProviderDataRequiresReview(String caseName) {
        Seed seed = seedOrder(BUYER, "PENDING_PAYMENT", "PENDING", UUID.randomUUID());
        ProviderOrderSnapshot valid = validSnapshot(seed);

        ProviderOrderSnapshot received = switch (caseName) {
            case "AMOUNT" ->
                snapshot(
                        seed,
                        seed.attemptId().toString(),
                        seed.amount().add(new BigDecimal("0.01")),
                        seed.amount().add(new BigDecimal("0.01")),
                        "PEN",
                        false,
                        "test-app");
            case "CURRENCY" ->
                snapshot(seed, seed.attemptId().toString(), seed.amount(), seed.amount(), "USD", false, "test-app");
            case "REFERENCE" ->
                snapshot(seed, UUID.randomUUID().toString(), seed.amount(), seed.amount(), "PEN", false, "test-app");
            case "APPLICATION" ->
                snapshot(seed, seed.attemptId().toString(), seed.amount(), seed.amount(), "PEN", false, "another-app");
            case "PROVIDER_LIVE_MODE" ->
                snapshot(seed, seed.attemptId().toString(), seed.amount(), seed.amount(), "PEN", true, "test-app");
            default -> valid;
        };

        String expectedReason = switch (caseName) {
            case "AMOUNT" -> "AMOUNT_MISMATCH";
            case "CURRENCY" -> "CURRENCY_MISMATCH";
            case "REFERENCE" -> "REFERENCE_MISMATCH";
            case "APPLICATION" -> "APPLICATION_MISMATCH";
            default -> "LIVE_MODE_MISMATCH";
        };

        stubProvider(seed, received);
        notifyProvider(seed, "NOTIFICATION_LIVE_MODE".equals(caseName));

        assertTables(seed, "PENDING_PAYMENT", false, "REVIEW_REQUIRED", expectedReason, 0, 0);
    }

    @Test
    void lateValidPaymentPaysPreviouslyFailedOrder() {
        Seed seed = seedOrder(BUYER, "PAYMENT_FAILED", "REJECTED", UUID.randomUUID());
        stubProvider(seed, validSnapshot(seed));

        notifyProvider(seed, false);

        assertTables(seed, "PAID", true, "APPROVED", null, 1, 1);
    }

    @Test
    void secondPaidAttemptDoesNotGrantOrPublishAgain() {
        UUID product = UUID.randomUUID();
        Seed first = seedOrder(BUYER, "PENDING_PAYMENT", "PENDING", product);
        stubProvider(first, validSnapshot(first));
        notifyProvider(first, false);
        assertTables(first, "PAID", true, "APPROVED", null, 1, 1);

        Seed second = seedAttempt(first);
        stubProvider(second, validSnapshot(second));
        notifyProvider(second, false);

        assertTables(first, "PAID", true, "APPROVED", null, 1, 1);
        assertTables(second, "PAID", true, "APPROVED", "DUPLICATE_PAYMENT", 1, 1);
        assertEquals(2, count("SELECT count(*) FROM payment_attempts WHERE order_id = ?", first.orderId()));
    }

    @Test
    void alreadyOwnedProductPaysNewOrderWithoutNewEntitlement() {
        UUID product = UUID.randomUUID();

        Seed first = seedOrder(BUYER, "PENDING_PAYMENT", "PENDING", product);
        stubProvider(first, validSnapshot(first));
        notifyProvider(first, false);
        assertTables(first, "PAID", true, "APPROVED", null, 1, 1);

        Seed second = seedOrder(BUYER, "PENDING_PAYMENT", "PENDING", product);
        stubProvider(second, validSnapshot(second));
        notifyProvider(second, false);

        assertTables(second, "PAID", true, "APPROVED", "DUPLICATE_PURCHASE", 0, 1);
        assertEquals(
                1,
                count("SELECT count(*) FROM entitlements " + "WHERE buyer_sub = ? AND product_id = ?", BUYER, product));
        assertEquals(2, count("SELECT count(*) FROM outbox_events"));
    }

    private Seed seedOrder(String buyerSub, String orderStatus, String attemptStatus, UUID... products) {
        UUID orderId = UUID.randomUUID();
        BigDecimal total = UNIT_PRICE.multiply(BigDecimal.valueOf(products.length));

        jdbc.update("""
                INSERT INTO orders
                    (id, buyer_sub, buyer_email,
                     total_amount, currency, status)
                VALUES (?, ?, ?, ?, 'PEN', ?)
                """, orderId, buyerSub, buyerSub + "@example.test", total, orderStatus);

        for (UUID product : products) {
            jdbc.update("""
                    INSERT INTO order_items
                        (id, order_id, product_id, sku, title,
                         unit_price, quantity, line_total)
                    VALUES (?, ?, ?, 'PRODUCT-TEST', 'Producto de prueba',
                            ?, 1, ?)
                    """, UUID.randomUUID(), orderId, product, UNIT_PRICE, UNIT_PRICE);
        }

        return insertAttempt(orderId, buyerSub, total, attemptStatus);
    }

    private Seed seedAttempt(Seed order) {
        return insertAttempt(order.orderId(), order.buyerSub(), order.amount(), "PENDING");
    }

    private Seed insertAttempt(UUID orderId, String buyerSub, BigDecimal amount, String status) {
        UUID attemptId = UUID.randomUUID();
        String providerOrderId = "ORDTEST" + UUID.randomUUID().toString().replace("-", "");

        jdbc.update("""
                INSERT INTO payment_attempts
                    (id, order_id, provider_order_id,
                     provider_idempotency_key, amount, currency, status)
                VALUES (?, ?, ?, ?, ?, 'PEN', ?)
                """, attemptId, orderId, providerOrderId, UUID.randomUUID(), amount, status);

        return new Seed(orderId, attemptId, providerOrderId, amount, buyerSub);
    }

    private ProviderOrderSnapshot validSnapshot(Seed seed) {
        return snapshot(seed, seed.attemptId().toString(), seed.amount(), seed.amount(), "PEN", false, "test-app");
    }

    private ProviderOrderSnapshot snapshot(
            Seed seed,
            String externalReference,
            BigDecimal totalAmount,
            BigDecimal totalPaidAmount,
            String currency,
            Boolean liveMode,
            String applicationId) {
        return new ProviderOrderSnapshot(
                seed.providerOrderId(),
                externalReference,
                totalAmount,
                totalPaidAmount,
                currency,
                "processed",
                "accredited",
                liveMode,
                applicationId);
    }

    private void stubProvider(Seed seed, ProviderOrderSnapshot snapshot) {
        when(paymentGateway.getOrder(seed.providerOrderId())).thenReturn(snapshot);
    }

    private void notifyProvider(Seed seed, Boolean notificationLiveMode) {
        webhook.process(
                UUID.randomUUID().toString(), seed.providerOrderId(), "order.processed", "{}", notificationLiveMode);
    }

    private void assertTables(
            Seed seed,
            String expectedOrderStatus,
            boolean expectedPaidAt,
            String expectedAttemptStatus,
            String expectedReviewReason,
            int expectedEntitlements,
            int expectedOutboxEvents) {
        // orders
        assertEquals(
                expectedOrderStatus,
                jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, seed.orderId()));
        boolean paidAt = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT paid_at IS NOT NULL FROM orders WHERE id = ?", Boolean.class, seed.orderId()));
        if (expectedPaidAt) {
            assertTrue(paidAt);
        } else {
            assertFalse(paidAt);
        }

        // payment_attempts
        assertEquals(
                expectedAttemptStatus,
                jdbc.queryForObject(
                        "SELECT status FROM payment_attempts WHERE id = ?", String.class, seed.attemptId()));
        assertEquals(
                expectedReviewReason,
                jdbc.queryForObject(
                        "SELECT review_reason FROM payment_attempts WHERE id = ?", String.class, seed.attemptId()));

        // entitlements
        assertEquals(
                expectedEntitlements, count("SELECT count(*) FROM entitlements WHERE order_id = ?", seed.orderId()));

        // outbox_events
        assertEquals(
                expectedOutboxEvents,
                count(
                        "SELECT count(*) FROM outbox_events " + "WHERE order_id = ? AND event_type = 'ORDER_PAID'",
                        seed.orderId()));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private record Seed(UUID orderId, UUID attemptId, String providerOrderId, BigDecimal amount, String buyerSub) {}
}
