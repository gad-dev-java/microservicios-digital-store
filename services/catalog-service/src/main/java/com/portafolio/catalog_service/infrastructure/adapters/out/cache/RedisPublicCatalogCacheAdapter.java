package com.portafolio.catalog_service.infrastructure.adapters.out.cache;

import com.portafolio.catalog_service.application.ports.in.ProductPage;
import com.portafolio.catalog_service.application.ports.out.PublicCatalogCachePort;
import com.portafolio.catalog_service.domain.models.Product;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RedisPublicCatalogCacheAdapter implements PublicCatalogCachePort {
    private static final String PREFIX = "catalog:public:v1:";
    private static final String GENERATION_KEY = PREFIX + "generation";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final CircuitBreaker breaker;
    private final AtomicReference<String> pendingGeneration = new AtomicReference<>();

    public RedisPublicCatalogCacheAdapter(
            StringRedisTemplate redis, JsonMapper json, @Qualifier("catalogCache") CircuitBreaker breaker) {
        this.redis = redis;
        this.json = json;
        this.breaker = breaker;
    }

    @Override
    public ProductPage page(String title, int page, int size, Supplier<ProductPage> database) {
        String normalized = title.toLowerCase(Locale.ROOT);
        String query =
                Base64.getUrlEncoder().withoutPadding().encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
        return load("page:" + query + ":" + page + ":" + size, ProductPage.class, database);
    }

    @Override
    public Product product(UUID id, Supplier<Product> database) {
        return load("product:" + id, Product.class, database);
    }

    @Override
    public void invalidate() {
        String next = UUID.randomUUID().toString();
        pendingGeneration.set(next);
        try {
            breaker.executeRunnable(() -> redis.opsForValue().set(GENERATION_KEY, next));
            pendingGeneration.compareAndSet(next, null);
        } catch (RuntimeException ignored) {
            // Las lecturas usarán PostgreSQL hasta poder registrar la invalidación.
        }
    }

    private <T> T load(String suffix, Class<T> type, Supplier<T> database) {
        String generation = generation();
        if (generation == null) {
            return database.get();
        }

        String key = PREFIX + generation + ":" + suffix;
        String cached;
        try {
            cached = breaker.executeSupplier(() -> redis.opsForValue().get(key));
        } catch (RuntimeException ignored) {
            return database.get();
        }

        if (cached != null) {
            try {
                return json.readValue(cached, type);
            } catch (RuntimeException ignored) {
                // Si el JSON está dañado, se vuelve a consultar PostgreSQL.
            }
        }

        T current = database.get();
        try {
            String encoded = json.writeValueAsString(current);
            breaker.executeRunnable(() -> redis.opsForValue().set(key, encoded, TTL));
        } catch (RuntimeException ignored) {
            // La escritura de caché nunca impide devolver el resultado.
        }
        return current;
    }

    private String generation() {
        String pending = pendingGeneration.get();
        if (pending != null) {
            try {
                breaker.executeRunnable(() -> redis.opsForValue().set(GENERATION_KEY, pending));
                pendingGeneration.compareAndSet(pending, null);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        try {
            return breaker.executeSupplier(() -> {
                String current = redis.opsForValue().get(GENERATION_KEY);
                if (current != null) {
                    return current;
                }

                String initial = UUID.randomUUID().toString();
                redis.opsForValue().setIfAbsent(GENERATION_KEY, initial);
                return redis.opsForValue().get(GENERATION_KEY);
            });
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
