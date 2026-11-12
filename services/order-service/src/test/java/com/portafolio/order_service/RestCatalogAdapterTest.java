package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import com.portafolio.order_service.infrastructure.adapters.out.RestCatalogAdapter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class RestCatalogAdapterTest {
    @Test
    void slowCatalogReturnsUnavailableAfterReadTimeout() throws Exception {
        try (ServerSocket slowServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread responder = Thread.ofVirtual().start(() -> {
                try (Socket ignored = slowServer.accept()) {
                    Thread.sleep(500);
                } catch (Exception ignored) {
                    // El test solo necesita mantener la conexión abierta.
                }
            });

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(1000);
            factory.setReadTimeout(100);

            RestClient client = RestClient.builder()
                    .requestFactory(factory)
                    .baseUrl("http://127.0.0.1:" + slowServer.getLocalPort())
                    .build();
            RestCatalogAdapter adapter = new RestCatalogAdapter(client, CircuitBreaker.ofDefaults("slow-catalog-test"));

            OrderException failure = assertThrows(OrderException.class, () -> adapter.getPublished(UUID.randomUUID()));

            assertEquals(Reason.CATALOG_UNAVAILABLE, failure.reason());
            responder.join(1000);
        }
    }
}
