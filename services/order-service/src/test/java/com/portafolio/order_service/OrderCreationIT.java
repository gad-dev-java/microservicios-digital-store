package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portafolio.order_service.application.ports.in.Command;
import com.portafolio.order_service.application.ports.in.CreateOrderUseCase;
import com.portafolio.order_service.application.ports.in.Result;
import com.portafolio.order_service.application.ports.out.CatalogPort;
import com.portafolio.order_service.application.ports.out.CatalogProduct;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public class OrderCreationIT {
    private static final String BUYER = "buyer-hu05";
    private static final String EMAIL = "buyer@example.test";
    private static final UUID PRODUCT = UUID.fromString("b632be69-fcb3-4a78-932c-9b2e8da5bca4");
    private static final UUID OTHER_PRODUCT = UUID.fromString("ca2c2b3a-8979-4e19-9d45-3262af86e21d");

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
    CreateOrderUseCase orders;

    @MockitoBean
    CatalogPort catalog;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        reset(catalog);
        jdbc.update("DELETE FROM entitlements");
        jdbc.update("DELETE FROM idempotency_requests");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void sameKeyAndBodyReturnSameOrder() throws Exception {
        when(catalog.getPublished(PRODUCT)).thenReturn(product("Guía Java", "39.90"));

        MvcResult first = submit("same-key", PRODUCT);
        MvcResult replay = submit("same-key", PRODUCT);

        assertEquals(201, first.getResponse().getStatus());
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(
                first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertCounts(1, 1, 1);
    }

    @Test
    void sameKeyWithDifferentBodyReturnsConflict() throws Exception {
        when(catalog.getPublished(PRODUCT)).thenReturn(product("Guía Java", "39.90"));

        assertEquals(201, submit("conflict-key", PRODUCT).getResponse().getStatus());
        MvcResult conflict = submit("conflict-key", OTHER_PRODUCT);

        assertEquals(409, conflict.getResponse().getStatus());
        assertTrue(conflict.getResponse().getContentAsString().contains("IDEMPOTENCY_CONFLICT"));
        assertCounts(1, 1, 1);
    }

    @Test
    void laterCatalogPriceDoesNotModifyStoredOrder() throws Exception {
        when(catalog.getPublished(PRODUCT)).thenReturn(product("Guía Java", "39.90"));
        assertEquals(201, submit("price-one", PRODUCT).getResponse().getStatus());

        UUID firstOrderId = jdbc.queryForObject("SELECT id FROM orders", UUID.class);
        when(catalog.getPublished(PRODUCT)).thenReturn(product("Guía Java nueva", "79.90"));
        assertEquals(201, submit("price-two", PRODUCT).getResponse().getStatus());

        BigDecimal storedPrice = jdbc.queryForObject(
                "SELECT unit_price FROM order_items WHERE order_id = ?", BigDecimal.class, firstOrderId);
        assertEquals(new BigDecimal("39.90"), storedPrice);
        assertCounts(2, 2, 2);
    }

    @Test
    void catalogFailureCreatesNoPartialOrder() throws Exception {
        when(catalog.getPublished(PRODUCT))
                .thenThrow(new OrderException(Reason.CATALOG_UNAVAILABLE, "Catalog no disponible"));

        MvcResult failure = submit("unavailable-key", PRODUCT);

        assertEquals(503, failure.getResponse().getStatus());
        assertEquals("5", failure.getResponse().getHeader("Retry-After"));
        assertTrue(failure.getResponse().getContentAsString().contains("CATALOG_UNAVAILABLE"));
        assertCounts(0, 0, 0);
    }

    @Test
    void alreadyOwnedProductIsRejectedBeforeCatalogCall() throws Exception {
        UUID previousOrder = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders
                    (id, buyer_sub, buyer_email, total_amount, currency, status, paid_at)
                VALUES (?, ?, ?, 39.90, 'PEN', 'PAID', now())
                """, previousOrder, BUYER, EMAIL);
        jdbc.update("""
                INSERT INTO order_items
                    (id, order_id, product_id, sku, title, unit_price, line_total)
                VALUES (?, ?, ?, 'JAVA', 'Guía Java', 39.90, 39.90)
                """, UUID.randomUUID(), previousOrder, PRODUCT);
        jdbc.update("""
                INSERT INTO entitlements (id, buyer_sub, product_id, order_id)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), BUYER, PRODUCT, previousOrder);

        MvcResult response = submit("owned-key", PRODUCT);

        assertEquals(409, response.getResponse().getStatus());
        assertTrue(response.getResponse().getContentAsString().contains("PRODUCT_ALREADY_OWNED"));
        assertCounts(1, 1, 0);
        verifyNoInteractions(catalog);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOnlyOneOrder() throws Exception {
        CountDownLatch bothReachedCatalog = new CountDownLatch(2);
        when(catalog.getPublished(PRODUCT)).thenAnswer(invocation -> {
            bothReachedCatalog.countDown();
            assertTrue(bothReachedCatalog.await(15, TimeUnit.SECONDS));
            return product("Guía Java", "39.90");
        });

        Command command = new Command(BUYER, EMAIL, java.util.List.of(PRODUCT), "concurrent-key");

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> orders.create(command));
            var second = pool.submit(() -> orders.create(command));

            Result one = first.get(30, TimeUnit.SECONDS);
            Result two = second.get(30, TimeUnit.SECONDS);

            assertEquals(one.order().id(), two.order().id());
            assertNotEquals(one.replayed(), two.replayed());
        }

        assertCounts(1, 1, 1);
    }

    private MvcResult submit(String key, UUID productId) throws Exception {
        String body = "{\"productIds\":[\"" + productId + "\"]}";
        return mvc.perform(post("/api/orders")
                        .with(jwt().jwt(token -> token.subject(BUYER)
                                        .claim("email", EMAIL)
                                        .claim("email_verified", true))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private CatalogProduct product(String title, String price) {
        return new CatalogProduct(PRODUCT, "JAVA", title, new BigDecimal(price), "PEN");
    }

    private void assertCounts(int expectedOrders, int expectedItems, int expectedKeys) {
        assertEquals(expectedOrders, jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class));
        assertEquals(expectedItems, jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class));
        assertEquals(expectedKeys, jdbc.queryForObject("SELECT count(*) FROM idempotency_requests", Integer.class));
    }
}
