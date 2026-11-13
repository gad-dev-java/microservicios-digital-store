package com.portafolio.order_service.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Configuration
public class CatalogClientConfig {
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build());
        return manager;
    }

    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder plainRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    @Bean("catalogRestClient")
    RestClient catalogRestClient(@LoadBalanced RestClient.Builder builder, OAuth2AuthorizedClientManager manager) {
        var interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setPrincipalResolver(new RequestAttributePrincipalResolver());

        return builder.baseUrl("http://catalog-service")
                .requestInterceptor(interceptor)
                .build();
    }

    @Bean("catalogCircuitBreaker")
    CircuitBreaker catalogCircuitBreaker() {
        var config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(3)
                .slowCallDurationThreshold(Duration.ofMillis(1500))
                .slowCallRateThreshold(100)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .recordException(error -> error instanceof HttpServerErrorException
                        || error instanceof ResourceAccessException
                        || (error instanceof IllegalStateException
                                && error.getMessage() != null
                                && error.getMessage().startsWith("No instances available")))
                .ignoreExceptions(HttpClientErrorException.class, OAuth2AuthorizationException.class)
                .build();

        return CircuitBreaker.of("catalog", config);
    }

    @Bean("catalogDownloadRestClient")
    RestClient catalogDownloadRestClient(
            @LoadBalanced RestClient.Builder builder, OAuth2AuthorizedClientManager manager) {
        var interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setPrincipalResolver(new RequestAttributePrincipalResolver());

        var javaClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        var factory = new JdkClientHttpRequestFactory(javaClient);
        factory.setReadTimeout(Duration.ofSeconds(60));

        return builder.baseUrl("http://catalog-service")
                .requestFactory(factory)
                .requestInterceptor(interceptor)
                .build();
    }
}
