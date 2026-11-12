package com.portafolio.order_service.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

class CatalogClientConfigTest {
    @Test
    void ignoresClientErrorsAndInvalidServiceCredentials() {
        var breaker = new CatalogClientConfig().catalogCircuitBreaker();

        assertThrows(
                HttpClientErrorException.class,
                () -> breaker.executeRunnable(() -> {
                    throw new HttpClientErrorException(HttpStatus.FORBIDDEN);
                }));
        assertThrows(
                OAuth2AuthorizationException.class,
                () -> breaker.executeRunnable(() -> {
                    throw new OAuth2AuthorizationException(new OAuth2Error("invalid_client"));
                }));

        assertEquals(0, breaker.getMetrics().getNumberOfBufferedCalls());
    }

    @Test
    void recordsServerErrors() {
        var breaker = new CatalogClientConfig().catalogCircuitBreaker();

        assertThrows(
                HttpServerErrorException.class,
                () -> breaker.executeRunnable(() -> {
                    throw new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
                }));

        assertEquals(1, breaker.getMetrics().getNumberOfFailedCalls());
    }
}
