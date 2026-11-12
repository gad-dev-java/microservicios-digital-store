package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portafolio.order_service.application.ports.out.CatalogPort;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.application.ports.out.ProviderOrderSnapshot;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {"MERCADOPAGO_APPLICATION_ID=test-app", "MERCADOPAGO_EXPECTED_LIVE_MODE=false"})
class AdminPaymentReviewIT {
    private static final String BUYER = "review-buyer";
    private static final String ADMIN = "review-admin";
    private static final String PROVIDER_ID = "ORDTESTADMIN123";
    private static final BigDecimal PRICE = new BigDecimal("39.90");
    private static final String CHECKOUT_URL = "https://www.mercadopago.com.pe/checkout/v1/redirect?order_id=ORDNEW123";

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
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

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
    void customerIsForbiddenAndAdminCanUseAllThreeRoutes() throws Exception {
        Seed seed = seedReview("UNRESOLVED_TIMEOUT", null);
        when(paymentGateway.findOrderIds(eq(seed.attemptId()), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        mvc.perform(get("/api/admin/orders/{orderId}/payment-attempts", seed.orderId())
                        .with(user(BUYER, "CUSTOMER")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/payment-attempts/{id}/reconcile", seed.attemptId())
                        .with(user(BUYER, "CUSTOMER")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/payment-attempts/{id}/resolve", seed.attemptId())
                        .with(user(BUYER, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResolution()))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/orders/{orderId}/payment-attempts", seed.orderId())
                        .with(user(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(seed.attemptId().toString()));

        mvc.perform(post("/api/admin/payment-attempts/{id}/reconcile", seed.attemptId())
                        .with(user(ADMIN, "ADMIN")))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/admin/payment-attempts/{id}/resolve", seed.attemptId())
                        .with(user(ADMIN, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResolution()))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"resolution\":\"ERROR\",\"acknowledgeLatePaymentRisk\":true}",
                "{\"resolution\":\"ERROR\",\"reason\":\"\",\"acknowledgeLatePaymentRisk\":true}",
                "{\"resolution\":\"ERROR\",\"reason\":\"Revisado\",\"acknowledgeLatePaymentRisk\":false}",
                "{\"resolution\":\"ERROR\",\"reason\":\"Revisado\"}",
                "{\"resolution\":\"APPROVED\",\"reason\":\"Revisado\",\"acknowledgeLatePaymentRisk\":true}"
            })
    void invalidResolutionDoesNotReleaseAttempt(String request) throws Exception {
        Seed seed = seedReview("UNRESOLVED_TIMEOUT", null);

        mvc.perform(post("/api/admin/payment-attempts/{id}/resolve", seed.attemptId())
                        .with(user(ADMIN, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());

        assertEquals("REVIEW_REQUIRED", attemptStatus(seed));
        assertEquals(null, value("SELECT resolved_by FROM payment_attempts WHERE id = ?", seed.attemptId()));
    }

    @Test
    void validResolutionRecordsAuditAndAllowsNewCheckout() throws Exception {
        Seed seed = seedReview("UNRESOLVED_TIMEOUT", null);

        mvc.perform(post("/api/admin/payment-attempts/{id}/resolve", seed.attemptId())
                        .with(user(ADMIN, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResolution()))
                .andExpect(status().isNoContent());

        assertEquals("ERROR", attemptStatus(seed));
        assertEquals(
                "UNRESOLVED_TIMEOUT",
                value("SELECT review_reason FROM payment_attempts WHERE id = ?", seed.attemptId()));
        assertEquals(
                "Revisé el historial del proveedor",
                value("SELECT resolution_reason FROM payment_attempts WHERE id = ?", seed.attemptId()));
        assertEquals(ADMIN, value("SELECT resolved_by FROM payment_attempts WHERE id = ?", seed.attemptId()));
        assertNotNull(jdbc.queryForObject(
                "SELECT resolved_at FROM payment_attempts WHERE id = ?", Timestamp.class, seed.attemptId()));

        when(paymentGateway.create(any(Attempt.class))).thenReturn(new ProviderResult("ORDNEW123", CHECKOUT_URL));

        mvc.perform(post("/api/orders/{id}/checkout", seed.orderId()).with(user(BUYER, "CUSTOMER")))
                .andExpect(status().isOk());

        assertEquals(2, count("SELECT count(*) FROM payment_attempts WHERE order_id = ?", seed.orderId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "REFERENCE_MISMATCH", "KNOWN_PROVIDER_ORDER"})
    void verifiedDiscrepancyOrKnownProviderOrderCannotBeClosed(String caseName) throws Exception {
        String reason = "KNOWN_PROVIDER_ORDER".equals(caseName) ? "UNRESOLVED_TIMEOUT" : caseName;
        String providerId = "KNOWN_PROVIDER_ORDER".equals(caseName) ? PROVIDER_ID : null;
        Seed seed = seedReview(reason, providerId);

        mvc.perform(post("/api/admin/payment-attempts/{id}/resolve", seed.attemptId())
                        .with(user(ADMIN, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validResolution()))
                .andExpect(status().isConflict());

        assertEquals("REVIEW_REQUIRED", attemptStatus(seed));
        assertEquals(null, value("SELECT resolution_reason FROM payment_attempts WHERE id = ?", seed.attemptId()));
    }

    @Test
    void emptyManualSearchKeepsReviewAndDoesNotCreateProviderOrder() throws Exception {
        Seed seed = seedReview("UNRESOLVED_TIMEOUT", null);
        when(paymentGateway.findOrderIds(eq(seed.attemptId()), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        mvc.perform(post("/api/admin/payment-attempts/{id}/reconcile", seed.attemptId())
                        .with(user(ADMIN, "ADMIN")))
                .andExpect(status().isNoContent());

        assertEquals("REVIEW_REQUIRED", attemptStatus(seed));
        assertEquals(
                "UNRESOLVED_TIMEOUT",
                value("SELECT review_reason FROM payment_attempts WHERE id = ?", seed.attemptId()));
        verify(paymentGateway, never()).create(any(Attempt.class));
    }

    @Test
    void validApprovedPaymentIsAppliedOnce() throws Exception {
        Seed seed = seedReview("UNRESOLVED_TIMEOUT", null);
        when(paymentGateway.findOrderIds(eq(seed.attemptId()), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(PROVIDER_ID));
        when(paymentGateway.getOrder(PROVIDER_ID))
                .thenReturn(snapshot(seed, PRICE, "PEN", seed.attemptId().toString()));

        mvc.perform(post("/api/admin/payment-attempts/{id}/reconcile", seed.attemptId())
                        .with(user(ADMIN, "ADMIN")))
                .andExpect(status().isNoContent());

        assertEquals("PAID", orderStatus(seed));
        assertEquals("APPROVED", attemptStatus(seed));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT paid_at IS NOT NULL FROM orders WHERE id = ?", Boolean.class, seed.orderId())));
        assertEquals(1, count("SELECT count(*) FROM entitlements WHERE order_id = ?", seed.orderId()));
        assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE order_id = ?", seed.orderId()));

        // Ya no está en revisión; repetir la acción no duplica efectos.
        mvc.perform(post("/api/admin/payment-attempts/{id}/reconcile", seed.attemptId())
                        .with(user(ADMIN, "ADMIN")))
                .andExpect(status().isConflict());

        assertEquals(1, count("SELECT count(*) FROM entitlements WHERE order_id = ?", seed.orderId()));
        assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE order_id = ?", seed.orderId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AMOUNT", "CURRENCY", "REFERENCE"})
    void inconsistentProviderDataNeverPaysOrder(String caseName) throws Exception {
        Seed seed = seedReview("UNRESOLVED_TIMEOUT", null);
        when(paymentGateway.findOrderIds(eq(seed.attemptId()), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(PROVIDER_ID));

        BigDecimal amount = "AMOUNT".equals(caseName) ? PRICE.add(new BigDecimal("1.00")) : PRICE;
        String currency = "CURRENCY".equals(caseName) ? "USD" : "PEN";
        String reference = "REFERENCE".equals(caseName)
                ? UUID.randomUUID().toString()
                : seed.attemptId().toString();

        when(paymentGateway.getOrder(PROVIDER_ID)).thenReturn(snapshot(seed, amount, currency, reference));

        mvc.perform(post("/api/admin/payment-attempts/{id}/reconcile", seed.attemptId())
                        .with(user(ADMIN, "ADMIN")))
                .andExpect(status().isNoContent());

        assertEquals("REVIEW_REQUIRED", attemptStatus(seed));
        assertEquals("CREATED", orderStatus(seed));
        assertEquals(
                caseName + "_MISMATCH",
                value("SELECT review_reason FROM payment_attempts WHERE id = ?", seed.attemptId()));
        assertEquals(0, count("SELECT count(*) FROM entitlements WHERE order_id = ?", seed.orderId()));
        assertEquals(0, count("SELECT count(*) FROM outbox_events WHERE order_id = ?", seed.orderId()));
    }

    @Test
    void historyIsChronologicalAndDoesNotExposeSecrets() throws Exception {
        Seed current = seedReview("UNRESOLVED_TIMEOUT", null);
        UUID previousId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO payment_attempts
                    (id, order_id, provider_idempotency_key,
                     amount, currency, status, created_at)
                VALUES (?, ?, ?, ?, 'PEN', 'REJECTED', ?)
                """,
                previousId,
                current.orderId(),
                UUID.randomUUID(),
                PRICE,
                Timestamp.from(Instant.now().minus(Duration.ofHours(48))));

        String body = mvc.perform(get("/api/admin/orders/{orderId}/payment-attempts", current.orderId())
                        .with(user(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(previousId.toString()))
                .andExpect(jsonPath("$[1].id").value(current.attemptId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("providerIdempotencyKey"));
        assertFalse(body.contains("checkoutUrl"));
        assertFalse(body.contains("buyerEmail"));
    }

    private Seed seedReview(String reviewReason, String providerOrderId) {
        UUID orderId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO orders
                    (id, buyer_sub, buyer_email,
                     total_amount, currency, status)
                VALUES (?, ?, ?, ?, 'PEN', 'CREATED')
                """, orderId, BUYER, BUYER + "@example.test", PRICE);

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
                    (id, order_id, provider_order_id,
                     provider_idempotency_key, amount, currency,
                     status, review_reason, created_at)
                VALUES (?, ?, ?, ?, ?, 'PEN',
                        'REVIEW_REQUIRED', ?, ?)
                """,
                attemptId,
                orderId,
                providerOrderId,
                UUID.randomUUID(),
                PRICE,
                reviewReason,
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))));

        return new Seed(orderId, attemptId);
    }

    private ProviderOrderSnapshot snapshot(Seed seed, BigDecimal amount, String currency, String externalReference) {
        return new ProviderOrderSnapshot(
                PROVIDER_ID, externalReference, amount, amount, currency, "processed", "accredited", false, "test-app");
    }

    private RequestPostProcessor user(String subject, String role) {
        return jwt().jwt(token -> token.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private String validResolution() {
        return """
                {
                  "resolution": "ERROR",
                  "reason": "Revisé el historial del proveedor",
                  "acknowledgeLatePaymentRisk": true
                }
                """;
    }

    private String attemptStatus(Seed seed) {
        return value("SELECT status FROM payment_attempts WHERE id = ?", seed.attemptId());
    }

    private String orderStatus(Seed seed) {
        return value("SELECT status FROM orders WHERE id = ?", seed.orderId());
    }

    private String value(String sql, UUID id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    private int count(String sql, UUID id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private record Seed(UUID orderId, UUID attemptId) {}
}
