package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portafolio.order_service.application.ports.out.CatalogPort;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.application.ports.out.ProviderOrderSnapshot;
import com.portafolio.order_service.application.ports.out.ProviderRateLimitException;
import com.portafolio.order_service.application.service.ReconcilePaymentsService;
import com.portafolio.order_service.domain.models.Attempt;
import com.portafolio.order_service.domain.models.ProviderResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {"MERCADOPAGO_APPLICATION_ID=test-app", "MERCADOPAGO_EXPECTED_LIVE_MODE=false"})
class PaymentReconciliationIT {
    private static final String BUYER = "reconciliation-buyer";
    private static final String PROVIDER_ID = "ORDTEST123";
    private static final String CHECKOUT_URL =
            "https://www.mercadopago.com.pe/checkout/v1/redirect?order_id=ORDTEST123";
    private static final BigDecimal PRICE = new BigDecimal("39.90");

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
    MockMvc mvc;

    @Autowired
    ReconcilePaymentsService reconciliation;

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
    void creatingFoundAtProviderBecomesPending() {
        Seed seed = seed("CREATING", 1);
        when(paymentGateway.findOrderIds(eq(seed.attemptId()), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(PROVIDER_ID));
        when(paymentGateway.getOrder(PROVIDER_ID)).thenReturn(snapshot(seed, "created", "created"));
        when(paymentGateway.checkoutFor(PROVIDER_ID)).thenReturn(new ProviderResult(PROVIDER_ID, CHECKOUT_URL));

        reconciliation.runBatch();

        assertEquals("PENDING", attemptStatus(seed));
        assertEquals(
                PROVIDER_ID, value("SELECT provider_order_id FROM payment_attempts WHERE id = ?", seed.attemptId()));
        assertEquals(CHECKOUT_URL, value("SELECT checkout_url FROM payment_attempts WHERE id = ?", seed.attemptId()));
        assertEquals("PENDING_PAYMENT", orderStatus(seed));
        verify(paymentGateway, never()).create(any(Attempt.class));
    }

    @Test
    void emptySearchRetriesWithOriginalAttemptKeyAmountAndItems() {
        Seed seed = seed("CREATING", 1);
        when(paymentGateway.findOrderIds(eq(seed.attemptId()), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(paymentGateway.create(any(Attempt.class))).thenReturn(new ProviderResult(PROVIDER_ID, CHECKOUT_URL));

        reconciliation.runBatch();

        ArgumentCaptor<Attempt> captured = ArgumentCaptor.forClass(Attempt.class);
        verify(paymentGateway).create(captured.capture());
        Attempt sent = captured.getValue();

        assertEquals(seed.attemptId(), sent.id());
        assertEquals(seed.orderId(), sent.orderId());
        assertEquals(seed.providerKey(), sent.providerIdempotencyKey());
        assertEquals(0, PRICE.compareTo(sent.amount()));
        assertEquals("PEN", sent.currency());
        assertEquals(BUYER + "@example.test", sent.buyerEmail());
        assertEquals(1, sent.items().size());
        assertEquals(seed.productId(), sent.items().getFirst().productId());
        assertEquals("Producto capturado", sent.items().getFirst().title());
        assertEquals(0, PRICE.compareTo(sent.items().getFirst().unitPrice()));
        assertEquals("PENDING", attemptStatus(seed));
        assertEquals(1, count("SELECT count(*) FROM payment_attempts WHERE order_id = ?", seed.orderId()));
    }

    @Test
    void emptySearchAfter24HoursRequiresReviewAndBlocksCheckout() throws Exception {
        Seed seed = seed("CREATING", 25);
        when(paymentGateway.findOrderIds(eq(seed.attemptId()), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        reconciliation.runBatch();

        assertEquals("REVIEW_REQUIRED", attemptStatus(seed));
        assertEquals(
                "UNRESOLVED_TIMEOUT",
                value("SELECT review_reason FROM payment_attempts WHERE id = ?", seed.attemptId()));
        assertEquals("CREATED", orderStatus(seed));
        verify(paymentGateway, never()).create(any(Attempt.class));

        mvc.perform(post("/api/orders/{id}/checkout", seed.orderId())
                        .with(jwt().jwt(token -> token.subject(BUYER))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));

        assertEquals(1, count("SELECT count(*) FROM payment_attempts WHERE order_id = ?", seed.orderId()));
    }

    @Test
    void pendingAccreditedPaymentGrantsAccessAndPublishesOnce() {
        Seed seed = seed("PENDING", 1);
        when(paymentGateway.getOrder(PROVIDER_ID)).thenReturn(snapshot(seed, "processed", "accredited"));

        reconciliation.runBatch();
        reconciliation.runBatch();

        assertEquals("PAID", orderStatus(seed));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT paid_at IS NOT NULL FROM orders WHERE id = ?", Boolean.class, seed.orderId())));
        assertEquals("APPROVED", attemptStatus(seed));
        assertEquals(1, count("SELECT count(*) FROM entitlements WHERE order_id = ?", seed.orderId()));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM outbox_events " + "WHERE order_id = ? AND event_type = 'ORDER_PAID'",
                        seed.orderId()));
    }

    @Test
    void pendingProcessingPaymentRemainsPendingAndIsPostponed() {
        Seed seed = seed("PENDING", 1);
        when(paymentGateway.getOrder(PROVIDER_ID)).thenReturn(snapshot(seed, "processing", "in_process"));
        Instant before = Instant.now();

        reconciliation.runBatch();

        assertEquals("PENDING", attemptStatus(seed));
        assertEquals("PENDING_PAYMENT", orderStatus(seed));
        assertEquals(
                "processing", value("SELECT provider_status FROM payment_attempts WHERE id = ?", seed.attemptId()));
        assertTrue(nextReconcileAt(seed).isAfter(before.plusSeconds(60)));
        assertEquals(0, count("SELECT count(*) FROM entitlements WHERE order_id = ?", seed.orderId()));
        assertEquals(0, count("SELECT count(*) FROM outbox_events WHERE order_id = ?", seed.orderId()));
    }

    @Test
    void multipleProviderOrdersRequireReviewWithoutChoosingOne() {
        Seed seed = seed("CREATING", 1);
        when(paymentGateway.findOrderIds(eq(seed.attemptId()), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of("ORDTESTA", "ORDTESTB"));

        reconciliation.runBatch();

        assertEquals("REVIEW_REQUIRED", attemptStatus(seed));
        assertEquals(
                "MULTIPLE_PROVIDER_ORDERS",
                value("SELECT review_reason FROM payment_attempts WHERE id = ?", seed.attemptId()));
        assertEquals(null, value("SELECT provider_order_id FROM payment_attempts WHERE id = ?", seed.attemptId()));
        verify(paymentGateway, never()).getOrder(any());
        verify(paymentGateway, never()).create(any(Attempt.class));
    }

    @Test
    void rateLimitRespectsRetryAfter() {
        Seed seed = seed("CREATING", 1);
        when(paymentGateway.findOrderIds(eq(seed.attemptId()), any(Instant.class), any(Instant.class)))
                .thenThrow(new ProviderRateLimitException(Duration.ofMinutes(5)));
        Instant before = Instant.now();

        reconciliation.runBatch();

        assertEquals("CREATING", attemptStatus(seed));
        assertFalse(nextReconcileAt(seed).isBefore(before.plus(Duration.ofMinutes(5))));
        verify(paymentGateway, never()).create(any(Attempt.class));
    }

    private Seed seed(String attemptStatus, int ageHours) {
        UUID orderId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID providerKey = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        String orderState = "PENDING".equals(attemptStatus) ? "PENDING_PAYMENT" : "CREATED";
        String providerId = "PENDING".equals(attemptStatus) ? PROVIDER_ID : null;
        String checkoutUrl = "PENDING".equals(attemptStatus) ? CHECKOUT_URL : null;

        jdbc.update("""
                INSERT INTO orders
                    (id, buyer_sub, buyer_email, total_amount, currency, status)
                VALUES (?, ?, ?, ?, 'PEN', ?)
                """, orderId, BUYER, BUYER + "@example.test", PRICE, orderState);

        jdbc.update("""
                INSERT INTO order_items
                    (id, order_id, product_id, sku, title,
                     unit_price, quantity, line_total)
                VALUES (?, ?, ?, 'DIGITAL-001', 'Producto capturado',
                        ?, 1, ?)
                """, UUID.randomUUID(), orderId, productId, PRICE, PRICE);

        jdbc.update(
                """
                INSERT INTO payment_attempts
                    (id, order_id, provider_order_id, provider_idempotency_key,
                     checkout_url, amount, currency, status, created_at,
                     next_reconcile_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PEN', ?, ?, now() - INTERVAL '1 second')
                """,
                attemptId,
                orderId,
                providerId,
                providerKey,
                checkoutUrl,
                PRICE,
                attemptStatus,
                Timestamp.from(Instant.now().minus(Duration.ofHours(ageHours))));

        return new Seed(orderId, attemptId, providerKey, productId);
    }

    private ProviderOrderSnapshot snapshot(Seed seed, String status, String detail) {
        return new ProviderOrderSnapshot(
                PROVIDER_ID,
                seed.attemptId().toString(),
                PRICE,
                "processed".equals(status) ? PRICE : BigDecimal.ZERO,
                "PEN",
                status,
                detail,
                false,
                "test-app");
    }

    private String attemptStatus(Seed seed) {
        return value("SELECT status FROM payment_attempts WHERE id = ?", seed.attemptId());
    }

    private String orderStatus(Seed seed) {
        return value("SELECT status FROM orders WHERE id = ?", seed.orderId());
    }

    private Instant nextReconcileAt(Seed seed) {
        return jdbc.queryForObject(
                "SELECT next_reconcile_at FROM payment_attempts WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(),
                seed.attemptId());
    }

    private String value(String sql, Object argument) {
        return jdbc.queryForObject(sql, String.class, argument);
    }

    private int count(String sql, Object argument) {
        return jdbc.queryForObject(sql, Integer.class, argument);
    }

    private record Seed(UUID orderId, UUID attemptId, UUID providerKey, UUID productId) {}
}
