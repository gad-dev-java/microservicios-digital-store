package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portafolio.order_service.application.ports.in.LibraryUseCase;
import com.portafolio.order_service.application.ports.out.CatalogDownloadPort;
import com.portafolio.order_service.application.ports.out.CatalogPort;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class LibraryAccessIT {
    private static final String BUYER_A = "buyer-a";
    private static final String BUYER_B = "buyer-b";

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
    CatalogDownloadPort downloads;

    @MockitoBean
    CatalogPort catalog;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        reset(downloads, catalog);
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM entitlements");
        jdbc.update("DELETE FROM idempotency_requests");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void buyerSeesOnlyOwnPaidProductsAndEmptyPages() throws Exception {
        UUID ownProduct = UUID.randomUUID();
        UUID otherProduct = UUID.randomUUID();
        seedPaidPurchase(BUYER_A, ownProduct, "JAVA-A", "Curso privado de A");
        seedPaidPurchase(BUYER_B, otherProduct, "JAVA-B", "Curso privado de B");

        String body = mvc.perform(get("/api/library")
                        .param("page", "0")
                        .param("size", "20")
                        .with(role(BUYER_A, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(ownProduct.toString()))
                .andExpect(jsonPath("$.items[0].title").value("Curso privado de A"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains(otherProduct.toString()));
        assertFalse(body.contains("Curso privado de B"));

        mvc.perform(get("/api/library").param("page", "5").with(role(BUYER_A, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mvc.perform(get("/api/library").param("page", "-1").with(role(BUYER_A, "CUSTOMER")))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/library").param("size", "51").with(role(BUYER_A, "CUSTOMER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherBuyerGets404BeforeCatalogIsCalled() throws Exception {
        UUID productId = UUID.randomUUID();
        seedPaidPurchase(BUYER_A, productId, "PRIVATE", "Contenido privado");

        String body = mvc.perform(get("/api/library/{id}/download", productId).with(role(BUYER_B, "CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LIBRARY_ITEM_NOT_FOUND"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("Contenido privado"));
        assertFalse(body.contains("PRIVATE"));
        verify(downloads, never()).metadata(any());
    }

    @Test
    void downloadRequiresCustomerAuthentication() throws Exception {
        UUID productId = UUID.randomUUID();

        mvc.perform(get("/api/library/{id}/download", productId)).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/library/{id}/download", productId).with(role("service-account", "CATALOG_INTERNAL_READ")))
                .andExpect(status().isForbidden());

        verify(downloads, never()).metadata(any());
    }

    @Test
    void ownerDownloadsFileWithExpectedHeadersAndBytes() throws Exception {
        UUID productId = UUID.randomUUID();
        seedPaidPurchase(BUYER_A, productId, "GUIDE", "Guía digital");
        byte[] bytes = "contenido de prueba".getBytes(StandardCharsets.UTF_8);

        when(downloads.metadata(productId))
                .thenReturn(new LibraryUseCase.Download("guia.pdf", "application/pdf", bytes.length));
        doAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(2);
                    output.write(bytes);
                    return null;
                })
                .when(downloads)
                .stream(eq(productId), eq((long) bytes.length), any(OutputStream.class));

        MvcResult pending = mvc.perform(
                        get("/api/library/{id}/download", productId).with(role(BUYER_A, "CUSTOMER")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));

        assertEquals("application/pdf", pending.getResponse().getContentType());
        assertTrue(
                pending.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION).contains("guia.pdf"));
        assertEquals("no-store", pending.getResponse().getHeader(HttpHeaders.CACHE_CONTROL));
        verify(downloads).metadata(productId);
        verify(downloads).stream(eq(productId), eq((long) bytes.length), any(OutputStream.class));
    }

    @Test
    void fifthConcurrentDownloadReceives429() throws Exception {
        UUID productId = UUID.randomUUID();
        seedPaidPurchase(BUYER_A, productId, "FILE", "Archivo");
        when(downloads.metadata(productId)).thenReturn(new LibraryUseCase.Download("archivo.txt", "text/plain", 1));

        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    OutputStream output = invocation.getArgument(2);
                    output.write('x');
                    return null;
                })
                .when(downloads)
                .stream(eq(productId), eq(1L), any(OutputStream.class));

        MvcResult[] pending = new MvcResult[4];
        try {
            for (int i = 0; i < pending.length; i++) {
                pending[i] = mvc.perform(
                                get("/api/library/{id}/download", productId).with(role(BUYER_A, "CUSTOMER")))
                        .andExpect(request().asyncStarted())
                        .andReturn();
            }

            assertTrue(entered.await(5, TimeUnit.SECONDS));

            mvc.perform(get("/api/library/{id}/download", productId).with(role(BUYER_A, "CUSTOMER")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("DOWNLOAD_BUSY"));
        } finally {
            release.countDown();
        }

        for (MvcResult result : pending) {
            mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        }
    }

    private UUID seedPaidPurchase(String buyerSub, UUID productId, String sku, String title) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders
                    (id, buyer_sub, buyer_email,
                     total_amount, currency, status, paid_at)
                VALUES (?, ?, ?, 12.00, 'PEN', 'PAID', now())
                """, orderId, buyerSub, buyerSub + "@example.test");

        jdbc.update("""
                INSERT INTO order_items
                    (id, order_id, product_id, sku, title,
                     unit_price, quantity, line_total)
                VALUES (?, ?, ?, ?, ?, 12.00, 1, 12.00)
                """, UUID.randomUUID(), orderId, productId, sku, title);

        jdbc.update("""
                INSERT INTO entitlements
                    (id, buyer_sub, product_id, order_id)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), buyerSub, productId, orderId);

        return orderId;
    }

    private RequestPostProcessor role(String subject, String role) {
        return jwt().jwt(token -> token.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
