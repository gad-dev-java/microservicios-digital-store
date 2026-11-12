package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portafolio.order_service.application.ports.in.CheckoutOrderUseCase;
import com.portafolio.order_service.application.ports.out.CatalogPort;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Attempt;
import com.portafolio.order_service.domain.models.ProviderResult;
import com.portafolio.order_service.domain.models.Reason;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class CheckoutOrderIT {
    private static final String BUYER = "checkout-buyer";
    private static final String OTHER_BUYER = "another-buyer";
    private static final UUID PRODUCT = UUID.fromString("b632be69-fcb3-4a78-932c-9b2e8da5bca4");

    private static final String CHECKOUT_URL =
            "https://www.mercadopago.com.pe/checkout/v1/redirect?order_id=ORDTEST123";
    private static final String PROVIDER_ORDER_ID = "ORDTEST123";

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

    @Autowired
    CheckoutOrderUseCase checkout;

    @MockitoBean
    PaymentGatewayPort paymentGateway;

    @MockitoBean
    CatalogPort catalog;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        reset(paymentGateway, catalog);

        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM entitlements");
        jdbc.update("DELETE FROM idempotency_requests");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void anotherBuyerGets404AndProviderIsNotCalled() throws Exception {
        UUID orderId = seedOrder();

        mvc.perform(post("/api/orders/{id}/checkout", orderId).with(customer(OTHER_BUYER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        verify(paymentGateway, never()).create(any(Attempt.class));
        assertAttemptCount(orderId, 0);
    }

    @Test
    void paidOrderAndAlreadyOwnedProductReturn409() throws Exception {
        UUID paidOrder = seedOrder();

        jdbc.update("UPDATE orders SET status = 'PAID', paid_at = now() WHERE id = ?", paidOrder);

        mvc.perform(post("/api/orders/{id}/checkout", paidOrder).with(customer(BUYER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));

        assertAttemptCount(paidOrder, 0);

        jdbc.update("UPDATE orders SET status = 'CREATED', paid_at = NULL WHERE id = ?", paidOrder);

        jdbc.update("""
                INSERT INTO entitlements
                    (id, buyer_sub, product_id, order_id)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), BUYER, PRODUCT, paidOrder);

        mvc.perform(post("/api/orders/{id}/checkout", paidOrder).with(customer(BUYER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_ALREADY_OWNED"));

        verify(paymentGateway, never()).create(any(Attempt.class));
        assertAttemptCount(paidOrder, 0);
    }

    @Test
    void concurrentCallsCreateOnlyOneActiveAttempt() throws Exception {
        UUID orderId = seedOrder();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);

        when(paymentGateway.create(any(Attempt.class))).thenAnswer(invocation -> {
            providerEntered.countDown();
            assertTrue(releaseProvider.await(15, TimeUnit.SECONDS));
            return providerResult();
        });

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> checkout.checkout(orderId, BUYER));

            try {
                assertTrue(providerEntered.await(15, TimeUnit.SECONDS));

                var second = pool.submit(() -> checkout.checkout(orderId, BUYER));
                ExecutionException failure =
                        assertThrows(ExecutionException.class, () -> second.get(15, TimeUnit.SECONDS));

                OrderException cause = assertInstanceOf(OrderException.class, failure.getCause());
                assertEquals(Reason.PAYMENT_IN_PROGRESS, cause.reason());
            } finally {
                releaseProvider.countDown();
            }

            first.get(15, TimeUnit.SECONDS);
        }

        assertAttemptCount(orderId, 1);
        assertEquals(1, jdbc.queryForObject("""
                        SELECT count(*)
                        FROM payment_attempts
                        WHERE order_id = ?
                          AND status IN ('CREATING', 'PENDING', 'REVIEW_REQUIRED')
                        """, Integer.class, orderId));

        verify(paymentGateway, times(1)).create(any(Attempt.class));
    }

    @Test
    void successfulCheckoutPersistsProviderDataAndCapturedAmounts() throws Exception {
        UUID orderId = seedOrder();
        when(paymentGateway.create(any(Attempt.class))).thenReturn(providerResult());

        mvc.perform(post("/api/orders/{id}/checkout", orderId).with(customer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl").value(CHECKOUT_URL))
                .andExpect(jsonPath("$.reused").value(false));

        assertEquals(
                PROVIDER_ORDER_ID,
                jdbc.queryForObject(
                        "SELECT provider_order_id FROM payment_attempts WHERE order_id = ?", String.class, orderId));
        assertEquals(
                CHECKOUT_URL,
                jdbc.queryForObject(
                        "SELECT checkout_url FROM payment_attempts WHERE order_id = ?", String.class, orderId));
        assertEquals(
                "PENDING",
                jdbc.queryForObject("SELECT status FROM payment_attempts WHERE order_id = ?", String.class, orderId));
        assertEquals(
                "PENDING_PAYMENT",
                jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId));

        ArgumentCaptor<Attempt> captured = ArgumentCaptor.forClass(Attempt.class);
        verify(paymentGateway).create(captured.capture());

        Attempt sent = captured.getValue();
        assertEquals(orderId, sent.orderId());
        assertEquals(new BigDecimal("39.90"), sent.amount());
        assertEquals("PEN", sent.currency());
        assertEquals(BUYER + "@example.test", sent.buyerEmail());
        assertEquals(1, sent.items().size());
        assertEquals(PRODUCT, sent.items().getFirst().productId());
        assertEquals("JAVA-ORIGINAL", sent.items().getFirst().sku());
        assertEquals("Título capturado", sent.items().getFirst().title());
        assertEquals(new BigDecimal("39.90"), sent.items().getFirst().unitPrice());

        UUID storedKey = jdbc.queryForObject(
                "SELECT provider_idempotency_key FROM payment_attempts WHERE order_id = ?", UUID.class, orderId);
        assertNotNull(storedKey);
        assertEquals(storedKey, sent.providerIdempotencyKey());
        verifyNoInteractions(catalog);
    }

    @Test
    void timeoutKeepsCreatingAttemptAndSameProviderKey() throws Exception {
        UUID orderId = seedOrder();

        when(paymentGateway.create(any(Attempt.class)))
                .thenThrow(new OrderException(Reason.PAYMENT_PROVIDER_UNAVAILABLE, "Tiempo de espera agotado"));

        mvc.perform(post("/api/orders/{id}/checkout", orderId).with(customer(BUYER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_UNAVAILABLE"));

        UUID originalKey = jdbc.queryForObject(
                "SELECT provider_idempotency_key FROM payment_attempts WHERE order_id = ?", UUID.class, orderId);
        assertNotNull(originalKey);
        assertEquals(
                "CREATING",
                jdbc.queryForObject("SELECT status FROM payment_attempts WHERE order_id = ?", String.class, orderId));
        assertAttemptCount(orderId, 1);

        mvc.perform(post("/api/orders/{id}/checkout", orderId).with(customer(BUYER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));

        assertAttemptCount(orderId, 1);
        assertEquals(
                originalKey,
                jdbc.queryForObject(
                        "SELECT provider_idempotency_key FROM payment_attempts WHERE order_id = ?",
                        UUID.class,
                        orderId));
        verify(paymentGateway, times(1)).create(any(Attempt.class));
    }

    @Test
    void pendingAttemptReusesCheckoutUrlWithoutCallingProviderAgain() throws Exception {
        UUID orderId = seedOrder();
        when(paymentGateway.create(any(Attempt.class))).thenReturn(providerResult());

        mvc.perform(post("/api/orders/{id}/checkout", orderId).with(customer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(false));

        mvc.perform(post("/api/orders/{id}/checkout", orderId).with(customer(BUYER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(true))
                .andExpect(jsonPath("$.checkoutUrl").value(CHECKOUT_URL));

        assertAttemptCount(orderId, 1);
        verify(paymentGateway, times(1)).create(any(Attempt.class));
    }

    private UUID seedOrder() {
        UUID orderId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO orders
                    (id, buyer_sub, buyer_email,
                     total_amount, currency, status)
                VALUES (?, ?, ?, 39.90, 'PEN', 'CREATED')
                """, orderId, BUYER, BUYER + "@example.test");

        jdbc.update("""
                INSERT INTO order_items
                    (id, order_id, product_id, sku, title,
                     unit_price, quantity, line_total)
                VALUES (?, ?, ?, 'JAVA-ORIGINAL', 'Título capturado',
                        39.90, 1, 39.90)
                """, UUID.randomUUID(), orderId, PRODUCT);

        return orderId;
    }

    private ProviderResult providerResult() {
        return new ProviderResult(PROVIDER_ORDER_ID, CHECKOUT_URL);
    }

    private RequestPostProcessor customer(String subject) {
        return jwt().jwt(token -> token.subject(subject)
                        .claim("email", subject + "@example.test")
                        .claim("email_verified", true))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private void assertAttemptCount(UUID orderId, int expected) {
        assertEquals(
                expected,
                jdbc.queryForObject(
                        "SELECT count(*) FROM payment_attempts WHERE order_id = ?", Integer.class, orderId));
    }
}
