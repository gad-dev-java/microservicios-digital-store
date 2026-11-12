package com.portafolio.order_service.infrastructure.adapters.out;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;
import static org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver.principal;

import com.portafolio.order_service.application.ports.out.CatalogPort;
import com.portafolio.order_service.application.ports.out.CatalogProduct;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class RestCatalogAdapter implements CatalogPort {
    private final RestClient client;
    private final CircuitBreaker breaker;

    public RestCatalogAdapter(
            @Qualifier("catalogRestClient") RestClient client,
            @Qualifier("catalogCircuitBreaker") CircuitBreaker breaker) {
        this.client = client;
        this.breaker = breaker;
    }

    @Override
    public CatalogProduct getPublished(UUID id) {
        try {
            CatalogResponse body = breaker.executeSupplier(() -> client.get()
                    .uri("/api/internal/catalog/products/{id}", id)
                    .attributes(clientRegistrationId("order-catalog"))
                    .attributes(principal("order-service"))
                    .retrieve()
                    .body(CatalogResponse.class));

            if (body == null
                    || body.id() == null
                    || body.price() == null
                    || body.sku() == null
                    || body.title() == null
                    || !"PEN".equals(body.currency())
                    || !id.equals(body.id())) {
                throw new OrderException(Reason.CATALOG_UNAVAILABLE, "Respuesta inválida de Catalog");
            }

            return new CatalogProduct(body.id(), body.sku(), body.title(), body.price(), body.currency());
        } catch (HttpClientErrorException.NotFound exception) {
            throw new OrderException(Reason.PRODUCT_NOT_FOUND, "Producto no publicado o inexistente");
        } catch (HttpClientErrorException exception) {
            log.warn(
                    "Catalog rechazó la consulta: HTTP {}, producto={}",
                    exception.getStatusCode().value(),
                    id);
            throw new OrderException(Reason.CATALOG_UNAVAILABLE, "No se pudo consultar Catalog");
        } catch (OAuth2AuthorizationException exception) {
            log.warn(
                    "Falló la autenticación de Order ante Catalog: error={}",
                    exception.getError().getErrorCode());
            throw new OrderException(Reason.CATALOG_UNAVAILABLE, "No se pudo consultar Catalog");
        } catch (OrderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn(
                    "Falló la consulta a Catalog: tipo={}, producto={}",
                    exception.getClass().getSimpleName(),
                    id);
            throw new OrderException(Reason.CATALOG_UNAVAILABLE, "Catalog no está disponible");
        }
    }
}
