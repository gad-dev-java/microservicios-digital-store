package com.portafolio.order_service.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class CatalogClientConfigTest {
    @Test
    void selectsKubernetesCatalogBuilderWithoutLoadBalancer() {
        contextRunner("k8s").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(
                    "kubernetesRestClientBuilder",
                    context.getBeanFactory().getBeanDefinition("catalogBuilder").getFactoryMethodName());
            context.getBean("catalogRestClient", RestClient.class);
            context.getBean("catalogDownloadRestClient", RestClient.class);
        });
    }

    @Test
    void keepsLoadBalancedCatalogBuilderOutsideKubernetes() {
        contextRunner("test").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(
                    "loadBalancedRestClientBuilder",
                    context.getBeanFactory().getBeanDefinition("catalogBuilder").getFactoryMethodName());
        });
    }

    private ApplicationContextRunner contextRunner(String profile) {
        var configurer = mock(RestClientBuilderConfigurer.class);
        when(configurer.configure(any(RestClient.Builder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withUserConfiguration(CatalogClientConfig.class)
                .withBean(RestClientBuilderConfigurer.class, () -> configurer)
                .withBean(ClientRegistrationRepository.class, () -> mock(ClientRegistrationRepository.class))
                .withBean(OAuth2AuthorizedClientService.class, () -> mock(OAuth2AuthorizedClientService.class));
    }

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
