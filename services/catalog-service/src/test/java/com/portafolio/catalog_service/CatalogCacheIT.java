package com.portafolio.catalog_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.portafolio.catalog_service.application.ports.in.BrowseCatalogUseCase;
import com.portafolio.catalog_service.application.ports.in.ManageProductsUseCase;
import com.portafolio.catalog_service.application.ports.out.AssetStoragePort;
import com.portafolio.catalog_service.application.ports.out.ProductRepositoryPort;
import com.portafolio.catalog_service.domain.exceptions.ProductNotFoundException;
import com.portafolio.catalog_service.domain.models.AssetMetadata;
import com.portafolio.catalog_service.domain.models.Product;
import com.portafolio.catalog_service.domain.models.ProductStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CatalogCacheIT {
    private static final String GENERATION_KEY = "catalog:public:v1:generation";
    private static final AssetMetadata FILE =
            new AssetMetadata("files/cache.bin", "cache.bin", "application/octet-stream", 5L, "a".repeat(64));

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
    BrowseCatalogUseCase catalog;

    @Autowired
    ManageProductsUseCase admin;

    @Autowired
    ProductRepositoryPort products;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    @Qualifier("catalogCache")
    CircuitBreaker breaker;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    AssetStoragePort files;

    private UUID publishedId;

    @BeforeEach
    void prepareData() {
        breaker.reset();
        redis.execute((RedisCallback<Void>) connection -> {
            connection.flushDb();
            return null;
        });
        jdbc.update("DELETE FROM products");

        Product published = Product.draft("CACHE-1", "Libro inicial", "Descripción inicial", new BigDecimal("10.00"))
                .withFile(FILE)
                .publish();

        products.save(published);
        publishedId = published.id();
    }

    @Test
    void cachesPublicListAndDetailButRepositoryReadsCurrentPrice() {
        assertThat(catalog.getPublished(publishedId).title()).isEqualTo("Libro inicial");
        assertThat(catalog.search("", 0, 20).items()).extracting(Product::title).containsExactly("Libro inicial");

        // Cambia PostgreSQL directamente para comprobar que las dos
        // lecturas públicas siguientes proceden de Redis.
        jdbc.update(
                "UPDATE products SET title = ?, price = ? WHERE id = ?",
                "Cambio directo",
                new BigDecimal("25.00"),
                publishedId);

        assertThat(catalog.getPublished(publishedId).title()).isEqualTo("Libro inicial");
        assertThat(catalog.search("", 0, 20).items()).extracting(Product::title).containsExactly("Libro inicial");

        // La futura ruta interna de precio debe usar este repositorio,
        // no el caso de uso público cacheado.
        assertThat(products.findById(publishedId).orElseThrow().price()).isEqualByComparingTo("25.00");
    }

    @Test
    void editingInvalidatesListAndDetailAfterCommit() {
        catalog.getPublished(publishedId);
        catalog.search("", 0, 20);
        String generationBefore = redis.opsForValue().get(GENERATION_KEY);

        admin.updateDetails(publishedId, "Libro actualizado", "Descripción actualizada", new BigDecimal("18.50"));

        assertThat(redis.opsForValue().get(GENERATION_KEY)).isNotEqualTo(generationBefore);
        assertThat(catalog.getPublished(publishedId).title()).isEqualTo("Libro actualizado");
        assertThat(catalog.getPublished(publishedId).price()).isEqualByComparingTo("18.50");
        assertThat(catalog.search("actualizado", 0, 20).totalItems()).isEqualTo(1);
        assertThat(catalog.search("inicial", 0, 20).totalItems()).isZero();
    }

    @Test
    void publishingAndArchivingInvalidatePublicCatalog() {
        Product draft = Product.draft("CACHE-2", "Segundo libro", "Descripción", new BigDecimal("12.00"))
                .withFile(FILE);
        products.save(draft);

        assertThat(catalog.search("", 0, 20).totalItems()).isEqualTo(1);

        when(files.matches(any(AssetMetadata.class))).thenReturn(true);
        admin.publish(draft.id());

        assertThat(catalog.search("", 0, 20).totalItems()).isEqualTo(2);
        assertThat(catalog.getPublished(draft.id()).status()).isEqualTo(ProductStatus.PUBLISHED);

        // Calienta el detalle antes de archivarlo.
        catalog.getPublished(publishedId);
        admin.archive(publishedId);

        assertThat(products.findById(publishedId).orElseThrow().status()).isEqualTo(ProductStatus.ARCHIVED);
        assertThat(catalog.search("", 0, 20).totalItems()).isEqualTo(1);
        assertThrows(ProductNotFoundException.class, () -> catalog.getPublished(publishedId));
    }

    @Test
    void redisOutageUsesPostgresAndPendingInvalidationWorksOnRecovery() {
        // Deja entradas antiguas en Redis.
        catalog.search("", 0, 20);
        catalog.getPublished(publishedId);
        String generationBefore = redis.opsForValue().get(GENERATION_KEY);

        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            admin.updateDetails(publishedId, "Libro sin Redis", "Editado durante la caída", new BigDecimal("30.00"));

            assertThat(catalog.getPublished(publishedId).title()).isEqualTo("Libro sin Redis");
            assertThat(catalog.search("", 0, 20).items())
                    .extracting(Product::title)
                    .containsExactly("Libro sin Redis");
        } finally {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }

        // Evita esperar los 10 segundos del estado OPEN durante esta prueba.
        breaker.reset();

        assertThat(catalog.getPublished(publishedId).price()).isEqualByComparingTo("30.00");
        assertThat(redis.opsForValue().get(GENERATION_KEY)).isNotEqualTo(generationBefore);
    }
}
