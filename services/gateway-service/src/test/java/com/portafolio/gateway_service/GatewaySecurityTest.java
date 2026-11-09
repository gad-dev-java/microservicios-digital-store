package com.portafolio.gateway_service;

import com.portafolio.gateway_service.config.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;

@WebFluxTest(controllers = GatewaySecurityTest.ProbeController.class)
@Import({SecurityConfig.class, GatewaySecurityTest.ProbeController.class})
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class GatewaySecurityTest {

    @Autowired
    WebTestClient web;

    @MockitoBean
    ReactiveJwtDecoder decoder;

    @BeforeEach
    void prepareTokens() {
        when(decoder.decode("customer"))
                .thenReturn(Mono.just(token("customer", "CUSTOMER")));
        when(decoder.decode("service"))
                .thenReturn(Mono.just(token("service", "CATALOG_INTERNAL_READ")));
    }

    @Test
    void privateRouteWithoutTokenReturns401() {
        web.get().uri("/api/orders/test").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void customerCannotAccessAdminOrInternalRoute() {
        web.get().uri("/api/admin/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer customer")
                .exchange().expectStatus().isForbidden();

        web.get().uri("/api/internal/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer customer")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void internalRouteIsDeniedEvenForServiceToken() {
        web.get().uri("/api/internal/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer service")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void serviceAccountCannotAccessCustomerOrders() {
        web.get().uri("/api/orders/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer service")
                .exchange().expectStatus().isForbidden();

        web.get().uri("/api/orders/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer customer")
                .exchange().expectStatus().isOk();
    }

    private Jwt token(String value, String role) {
        return Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject(value)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/orders/test")
        String order() {
            return "ok";
        }

        @GetMapping("/api/admin/test")
        String admin() {
            return "ok";
        }

        @GetMapping("/api/internal/test")
        String internal() {
            return "ok";
        }
    }
}
