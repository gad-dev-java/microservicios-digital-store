package com.portafolio.order_service.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Configuration
public class MercadoPagoConfig {
    @Bean("mercadoPagoRestClient")
    RestClient mercadoPagoRestClient(@Qualifier("plainRestClientBuilder") RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));

        return builder.requestFactory(factory)
                .baseUrl("https://api.mercadopago.com")
                .build();
    }

    @Bean("mercadoPagoCircuitBreaker")
    CircuitBreaker mercadoPagoCircuitBreaker() {
        var config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(3)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .slowCallRateThreshold(100)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .recordException(error -> error instanceof HttpServerErrorException
                        || error instanceof ResourceAccessException
                        || error instanceof HttpClientErrorException.TooManyRequests)
                .build();

        return CircuitBreaker.of("mercadoPago", config);
    }
}
