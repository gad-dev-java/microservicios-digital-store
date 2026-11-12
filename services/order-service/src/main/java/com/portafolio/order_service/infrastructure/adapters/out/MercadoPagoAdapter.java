package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Attempt;
import com.portafolio.order_service.domain.models.ProviderResult;
import com.portafolio.order_service.domain.models.Reason;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            throw unavailable();
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
    public void verifyOrderExists(String providerOrderId) {
        requireConfigured();

        try {
            Map<?, ?> response = breaker.executeSupplier(() -> client.get()
                    .uri("/v1/orders/{id}", providerOrderId)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class));

            if (response == null || !providerOrderId.equals(string(response, "id"))) {
                throw unavailable();
            }
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
}
