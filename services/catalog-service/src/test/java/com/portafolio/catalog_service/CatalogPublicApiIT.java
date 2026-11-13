package com.portafolio.catalog_service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureTracing
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CatalogPublicApiIT {
    private static final UUID PUBLISHED_ID = UUID.fromString("f6e1df28-d585-4bfa-a251-8f49a14ff60e");
    private static final UUID DRAFT_ID = UUID.fromString("29ecbb57-7c0c-45af-8a04-6b3666e0b7f0");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    StringRedisTemplate redis;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void prepareData() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.flushDb();
            return null;
        });
        jdbc.update("DELETE FROM products");

        insert(PUBLISHED_ID, "JAVA-21", "Guía Java 21", "PUBLISHED");
        insert(UUID.randomUUID(), "SQL-101", "Curso SQL_101", "PUBLISHED");
        insert(DRAFT_ID, "DRAFT-1", "Guía secreta", "DRAFT");
    }

    @Test
    void listsOnlyPublishedProducts() throws Exception {
        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Curso SQL_101"))
                .andExpect(jsonPath("$.items[1].title").value("Guía Java 21"))
                .andExpect(jsonPath("$.items[0].storageKey").doesNotExist());
    }

    @Test
    void searchesIgnoringCaseAndTreatsUnderscoreLiterally() throws Exception {
        mvc.perform(get("/api/products").param("q", "  JAVA  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));

        mvc.perform(get("/api/products").param("q", "_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Curso SQL_101"));
    }

    @Test
    void paginatesAndRejectsInvalidParameters() throws Exception {
        mvc.perform(get("/api/products").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Guía Java 21"));

        mvc.perform(get("/api/products").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CATALOG_QUERY"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void hidesUnpublishedAndMissingProducts() throws Exception {
        mvc.perform(get("/api/products/{id}", PUBLISHED_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").exists());

        mvc.perform(get("/api/products/{id}", DRAFT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        mvc.perform(get("/api/products/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    private void insert(UUID id, String sku, String title, String status) {
        jdbc.update(
                """
                INSERT INTO products (
                    id, sku, title, description, price, currency, status,
                    storage_key, file_name, content_type, file_size, checksum_sha256
                ) VALUES (?, ?, ?, 'Descripción de prueba', 39.90, 'PEN', ?,
                          ?, ?, ?, ?, ?)
                """,
                id,
                sku,
                title,
                status,
                status.equals("PUBLISHED") ? "test-file.txt" : null,
                status.equals("PUBLISHED") ? "test-file.txt" : null,
                status.equals("PUBLISHED") ? "text/plain" : null,
                status.equals("PUBLISHED") ? 5L : null,
                status.equals("PUBLISHED") ? "a".repeat(64) : null);
    }
}
