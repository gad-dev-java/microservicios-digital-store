package com.portafolio.order_service;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portafolio.order_service.infrastructure.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = OrderSecurityTest.ProbeController.class)
@Import({SecurityConfig.class, OrderSecurityTest.ProbeController.class})
@ActiveProfiles("test")
class OrderSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder decoder;

    @BeforeEach
    void prepareTokens() {
        when(decoder.decode("customer")).thenReturn(token("customer", "CUSTOMER"));
        when(decoder.decode("service")).thenReturn(token("service", "CATALOG_INTERNAL_READ"));
    }

    @Test
    void privateRouteWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/orders/test")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerCanAccessOrdersButNotAdmin() throws Exception {
        mvc.perform(get("/api/orders/test").header(HttpHeaders.AUTHORIZATION, "Bearer customer"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/test").header(HttpHeaders.AUTHORIZATION, "Bearer customer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceAccountCannotActAsCustomer() throws Exception {
        mvc.perform(get("/api/orders/test").header(HttpHeaders.AUTHORIZATION, "Bearer service"))
                .andExpect(status().isForbidden());
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
    }
}
