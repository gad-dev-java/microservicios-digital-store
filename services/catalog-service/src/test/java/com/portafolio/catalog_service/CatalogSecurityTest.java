package com.portafolio.catalog_service;

import com.portafolio.catalog_service.infrastructure.config.SecurityConfig;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CatalogSecurityTest.ProbeController.class)
@Import({SecurityConfig.class, CatalogSecurityTest.ProbeController.class})
@ActiveProfiles("test")
class CatalogSecurityTest {

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
    void internalRouteWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/internal/test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotAccessAdminOrInternalRoute() throws Exception {
        mvc.perform(get("/api/admin/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer customer"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/internal/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer customer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceRoleCanAccessInternalRoute() throws Exception {
        mvc.perform(get("/api/internal/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer service"))
                .andExpect(status().isOk());
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
        @GetMapping("/api/internal/test")
        String internal() {
            return "ok";
        }

        @GetMapping("/api/admin/test")
        String admin() {
            return "ok";
        }
    }
}
