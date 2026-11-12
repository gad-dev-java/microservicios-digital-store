package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.application.ports.out.ProviderOrderSnapshot;
import com.portafolio.order_service.application.ports.out.ProviderRateLimitException;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Attempt;
import com.portafolio.order_service.domain.models.ProviderResult;
import com.portafolio.order_service.domain.models.Reason;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class MercadoPagoAdapter implements PaymentGatewayPort {
    private final RestClient client;
    private final CircuitBreaker breaker;
    private final String accessToken;

    public MercadoPagoAdapter(
            @Qualifier("mercadoPagoRestClient") RestClient client,
            @Qualifier("mercadoPagoCircuitBreaker") CircuitBreaker breaker,
            @Value("${MERCADOPAGO_ACCESS_TOKEN:}") String accessToken) {
        this.client = client;
        this.breaker = breaker;
        this.accessToken = accessToken;
    }

    @Override
    public void requireConfigured() {
        if (accessToken == null || accessToken.isBlank()) {
            throw new OrderException(
                    Reason.PAYMENT_PROVIDER_UNAVAILABLE, "Falta configurar el token de prueba de Mercado Pago");
        }
    }

    @Override
    public ProviderResult create(Attempt attempt) {
        requireConfigured();

        List<Map<String, Object>> items = attempt.items().stream()
                .map(item -> Map.<String, Object>of(
                        "title", item.title(),
                        "unit_price", item.unitPrice().toPlainString(),
                        "quantity", 1))
                .toList();

        Map<String, Object> request = Map.of(
                "type",
                "online",
                "processing_mode",
                "manual",
                "capture_mode",
                "automatic_async",
                "total_amount",
                attempt.amount().toPlainString(),
                "external_reference",
                attempt.id().toString(),
                "payer",
                Map.of("email", attempt.buyerEmail()),
                "items",
                items);

        try {
            Map<?, ?> response = breaker.executeSupplier(() -> client.post()
                    .uri("/v1/orders")
                    .header("Authorization", "Bearer " + accessToken)
                    .header(
                            "X-Idempotency-Key",
                            attempt.providerIdempotencyKey().toString())
                    .body(request)
                    .retrieve()
                    .body(Map.class));

            return validateResponse(response, attempt);
        } catch (RestClientResponseException exception) {
            log.warn(
                    "Mercado Pago rechazó la creación: HTTP {}, intento={}",
                    exception.getStatusCode().value(),
                    attempt.id());
            throw providerError(exception);
        } catch (OrderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn(
                    "Falló la creación en Mercado Pago: tipo={}, intento={}",
                    exception.getClass().getSimpleName(),
                    attempt.id());
            throw unavailable();
        }
    }

    @Override
    public ProviderOrderSnapshot getOrder(String providerOrderId) {
        requireConfigured();

        try {
            Map<?, ?> response = breaker.executeSupplier(() -> client.get()
                    .uri("/v1/orders/{id}", providerOrderId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class));

            if (response == null || !providerOrderId.equals(scalar(response, "id"))) {
                throw unavailable();
            }

            String applicationId = null;
            if (response.get("integration_data") instanceof Map<?, ?> integration) {
                applicationId = scalar(integration, "application_id");
            }

            Boolean liveMode = response.get("live_mode") instanceof Boolean value ? value : null;

            return new ProviderOrderSnapshot(
                    providerOrderId,
                    scalar(response, "external_reference"),
                    decimal(response, "total_amount"),
                    decimal(response, "total_paid_amount"),
                    scalar(response, "currency"),
                    scalar(response, "status"),
                    scalar(response, "status_detail"),
                    liveMode,
                    applicationId);

        } catch (RestClientResponseException exception) {
            throw providerError(exception);
        } catch (OrderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn(
                    "No se pudo consultar la orden de Mercado Pago: tipo={}, providerOrderId={}",
                    exception.getClass().getSimpleName(),
                    providerOrderId);
            throw unavailable();
        }
    }

    @Override
    public List<String> findOrderIds(UUID externalReference, Instant begin, Instant end) {
        requireConfigured();

        int offset = 0;
        int limit = 20;
        Set<String> ids = new LinkedHashSet<>();

        try {
            while (true) {
                int currentOffset = offset;
                Map<?, ?> page = breaker.executeSupplier(() -> client.get()
                        .uri(builder -> builder.path("/v1/orders")
                                .queryParam("begin_date", begin.toString())
                                .queryParam("end_date", end.toString())
                                .queryParam("external_reference", externalReference.toString())
                                .queryParam("offset", currentOffset)
                                .queryParam("limit", limit)
                                .build())
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(Map.class));

                if (page == null
                        || !(page.get("data") instanceof List<?> data)
                        || !(page.get("paging") instanceof Map<?, ?> paging)) {
                    throw unavailable();
                }

                for (Object element : data) {
                    if (element instanceof Map<?, ?> order
                            && externalReference.toString().equals(scalar(order, "external_reference"))) {
                        String id = scalar(order, "id");
                        if (id != null && !id.isBlank()) {
                            ids.add(id);
                        }
                    }
                }

                String totalText = scalar(paging, "total");
                if (totalText == null) {
                    throw unavailable();
                }

                int total = Integer.parseInt(totalText);
                offset += limit;

                if (offset >= total) {
                    return new ArrayList<>(ids);
                }
                if (data.isEmpty()) {
                    throw unavailable();
                }
            }
        } catch (RestClientResponseException exception) {
            throw providerError(exception);
        } catch (OrderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    @Override
    public ProviderResult checkoutFor(String providerOrderId) {
        requireConfigured();

        try {
            Map<?, ?> response = breaker.executeSupplier(() -> client.get()
                    .uri("/v1/orders/{id}", providerOrderId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class));

            if (response == null || !providerOrderId.equals(scalar(response, "id"))) {
                throw unavailable();
            }

            String checkoutUrl = scalar(response, "checkout_url");
            if (checkoutUrl != null && !validCheckoutUrl(checkoutUrl)) {
                throw unavailable();
            }

            return new ProviderResult(providerOrderId, checkoutUrl);
        } catch (RestClientResponseException exception) {
            throw providerError(exception);
        } catch (OrderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private OrderException providerError(RestClientResponseException exception) {
        if (exception.getStatusCode().value() != 429) {
            return unavailable();
        }

        String value = exception.getResponseHeaders() == null
                ? null
                : exception.getResponseHeaders().getFirst("Retry-After");

        Duration delay = Duration.ofSeconds(30);
        if (value != null) {
            try {
                delay = Duration.ofSeconds(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                try {
                    Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
                    delay = Duration.between(Instant.now(), retryAt);
                } catch (RuntimeException invalidDate) {
                    delay = Duration.ofSeconds(30);
                }
            }
        }

        long seconds = Math.max(1, delay.toSeconds());
        return new ProviderRateLimitException(Duration.ofSeconds(seconds));
    }

    private ProviderResult validateResponse(Map<?, ?> response, Attempt attempt) {
        if (response == null) throw unavailable();

        String providerId = string(response, "id");
        String checkoutUrl = string(response, "checkout_url");
        String reference = string(response, "external_reference");
        String currency = string(response, "currency");
        String amount = string(response, "total_amount");
        String status = string(response, "status");

        if (providerId == null
                || providerId.isBlank()
                || providerId.length() > 128
                || !attempt.id().toString().equals(reference)
                || !attempt.currency().equals(currency)
                || !"created".equals(status)
                || amount == null
                || new BigDecimal(amount).compareTo(attempt.amount()) != 0
                || !validCheckoutUrl(checkoutUrl)) {
            throw unavailable();
        }

        return new ProviderResult(providerId, checkoutUrl);
    }

    private boolean validCheckoutUrl(String value) {
        if (value == null) return false;
        URI uri = URI.create(value);
        String host = uri.getHost();
        if (host == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }

        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("mercadopago.com.pe") || normalized.endsWith(".mercadopago.com.pe");
    }

    private String string(Map<?, ?> response, String key) {
        Object value = response.get(key);
        return value instanceof String text ? text : null;
    }

    private OrderException unavailable() {
        return new OrderException(Reason.PAYMENT_PROVIDER_UNAVAILABLE, "No se pudo abrir el checkout de prueba");
    }

    private String scalar(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private BigDecimal decimal(Map<?, ?> values, String key) {
        String value = scalar(values, key);
        if (value == null) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
