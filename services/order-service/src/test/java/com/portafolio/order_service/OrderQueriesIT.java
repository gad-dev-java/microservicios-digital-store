package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portafolio.order_service.application.ports.out.CatalogPort;
import com.portafolio.order_service.application.ports.out.CatalogProduct;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class OrderQueriesIT {
    private static final String BUYER_A = "buyer-a";
    private static final String BUYER_B = "buyer-b";
    private static final String EMAIL_A = "buyer-a@example.test";
    private static final String EMAIL_B = "buyer-b@example.test";

    private static final UUID PRODUCT_A = UUID.fromString("b632be69-fcb3-4a78-932c-9b2e8da5bca4");
    private static final UUID PRODUCT_B = UUID.fromString("ca2c2b3a-8979-4e19-9d45-3262af86e21d");

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
    CatalogPort catalog;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        reset(catalog);

        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM entitlements");
        jdbc.update("DELETE FROM idempotency_requests");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void buyerSeesOnlyOwnOrders() throws Exception {
        UUID ownOrder = seedOrder(BUYER_A, PRODUCT_A, "JAVA-A", "Guía de A", "39.90");
        UUID otherOrder = seedOrder(BUYER_B, PRODUCT_B, "JAVA-B", "Guía de B", "59.90");

        String body = mvc.perform(
                        get("/api/orders/me").param("buyerSub", BUYER_B).with(role(BUYER_A, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(ownOrder.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains(otherOrder.toString()));
        assertFalse(body.contains(EMAIL_B));
    }

    @Test
    void anotherBuyerGets404WithoutOrderData() throws Exception {
        UUID orderId = seedOrder(BUYER_A, PRODUCT_A, "SECRET-SKU", "Título privado", "39.90");

        String body = mvc.perform(get("/api/orders/{id}", orderId).with(role(BUYER_B, "CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains(EMAIL_A));
        assertFalse(body.contains("Título privado"));
        assertFalse(body.contains("SECRET-SKU"));
        assertFalse(body.contains("totalAmount"));
        assertFalse(body.contains("unitPrice"));
    }

    @Test
    void adminSeesAllOrdersButCustomerCannotUseAdminRoute() throws Exception {
        seedOrder(BUYER_A, PRODUCT_A, "JAVA-A", "Guía de A", "39.90");
        seedOrder(BUYER_B, PRODUCT_B, "JAVA-B", "Guía de B", "59.90");

        mvc.perform(get("/api/admin/orders").with(role(BUYER_A, "CUSTOMER"))).andExpect(status().isForbidden());

        String body = mvc.perform(get("/api/admin/orders").with(role("admin-user", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(body.contains(EMAIL_A));
        assertTrue(body.contains(EMAIL_B));
    }

    @Test
    void adminSeesPaymentStatusAndReviewReason() throws Exception {
        UUID orderId = seedOrder(BUYER_A, PRODUCT_A, "JAVA-A", "Guía de A", "39.90");

        jdbc.update("""
                INSERT INTO payment_attempts
                    (id, order_id, provider_idempotency_key,
                     amount, currency, status, review_reason)
                VALUES (?, ?, ?, 39.90, 'PEN',
                        'REVIEW_REQUIRED', 'UNRESOLVED_TIMEOUT')
                """, UUID.randomUUID(), orderId, UUID.randomUUID());

        mvc.perform(get("/api/admin/orders").with(role("admin-user", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(orderId.toString()))
                .andExpect(jsonPath("$.items[0].paymentStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.items[0].reviewReason").value("UNRESOLVED_TIMEOUT"));
    }

    @Test
    void invalidPaginationReturns400AndEmptyPageReturns200() throws Exception {
        seedOrder(BUYER_A, PRODUCT_A, "JAVA-A", "Guía de A", "39.90");
        seedOrder(BUYER_A, PRODUCT_B, "JAVA-B", "Guía de B", "59.90");

        mvc.perform(get("/api/orders/me").param("page", "-1").with(role(BUYER_A, "CUSTOMER")))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/orders/me").param("size", "0").with(role(BUYER_A, "CUSTOMER")))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/orders/me").param("size", "51").with(role(BUYER_A, "CUSTOMER")))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/orders/me").param("page", "4").param("size", "1").with(role(BUYER_A, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void detailKeepsCapturedValuesAfterCatalogChanges() throws Exception {
        when(catalog.getPublished(PRODUCT_A))
                .thenReturn(new CatalogProduct(
                        PRODUCT_A, "JAVA-ORIGINAL", "Título original", new BigDecimal("39.90"), "PEN"));

        mvc.perform(post("/api/orders")
                        .with(role(BUYER_A, "CUSTOMER"))
                        .header("Idempotency-Key", "captured-values-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"" + PRODUCT_A + "\"]}"))
                .andExpect(status().isCreated());

        UUID orderId = jdbc.queryForObject("SELECT id FROM orders WHERE buyer_sub = ?", UUID.class, BUYER_A);

        when(catalog.getPublished(PRODUCT_A))
                .thenReturn(
                        new CatalogProduct(PRODUCT_A, "JAVA-NUEVO", "Título nuevo", new BigDecimal("79.90"), "PEN"));

        mvc.perform(get("/api/orders/{id}", orderId).with(role(BUYER_A, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value(orderId.toString()))
                .andExpect(jsonPath("$.order.totalAmount").value(39.90))
                .andExpect(jsonPath("$.items[0].sku").value("JAVA-ORIGINAL"))
                .andExpect(jsonPath("$.items[0].title").value("Título original"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(39.90));

        verify(catalog, times(1)).getPublished(PRODUCT_A);
    }

    private UUID seedOrder(String buyerSub, UUID productId, String sku, String title, String price) {
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal(price);

        jdbc.update("""
                INSERT INTO orders
                    (id, buyer_sub, buyer_email,
                     total_amount, currency, status)
                VALUES (?, ?, ?, ?, 'PEN', 'CREATED')
                """, orderId, buyerSub, buyerSub + "@example.test", amount);

        jdbc.update("""
                INSERT INTO order_items
                    (id, order_id, product_id, sku, title,
                     unit_price, quantity, line_total)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                """, UUID.randomUUID(), orderId, productId, sku, title, amount, amount);

        return orderId;
    }

    private RequestPostProcessor role(String subject, String role) {
        return jwt().jwt(token -> token.subject(subject)
                        .claim("email", subject + "@example.test")
                        .claim("email_verified", true))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
