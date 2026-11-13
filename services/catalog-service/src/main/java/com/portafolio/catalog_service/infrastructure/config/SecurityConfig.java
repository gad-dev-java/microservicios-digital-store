package com.portafolio.catalog_service.infrastructure.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new RealmRoleConverter());

        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(
                        requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC)
                                .permitAll()
                                .requestMatchers("/actuator/health", "/actuator/info")
                                .permitAll()
                                .requestMatchers("/api/internal/**")
                                .hasRole("CATALOG_INTERNAL_READ")
                                .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**")
                                .permitAll()
                                .requestMatchers("/api/admin/**")
                                .hasRole("ADMIN")
                                .anyRequest()
                                .denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .build();
    }
}
