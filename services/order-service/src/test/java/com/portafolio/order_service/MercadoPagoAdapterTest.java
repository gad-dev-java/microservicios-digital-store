package com.portafolio.order_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.portafolio.order_service.domain.models.Attempt;
import com.portafolio.order_service.domain.models.OrderItem;
import com.portafolio.order_service.domain.models.ProviderResult;
import com.portafolio.order_service.infrastructure.adapters.out.MercadoPagoAdapter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class MercadoPagoAdapterTest {
    @Test
    void sendsTokenIdempotencyKeyAndCapturedItems() {
        UUID attemptId = UUID.randomUUID();
        UUID providerKey = UUID.randomUUID();

        Attempt attempt = new Attempt(
                attemptId,
                UUID.randomUUID(),
                providerKey,
                new BigDecimal("39.90"),
                "PEN",
                "buyer@example.test",
                List.of(new OrderItem(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "JAVA-ORIGINAL",
                        "Título capturado",
                        new BigDecimal("39.90"))));

        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.mercadopago.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        MercadoPagoAdapter adapter =
                new MercadoPagoAdapter(builder.build(), CircuitBreaker.ofDefaults("mercadoPago-test"), "test-token");

        String checkoutUrl = "https://www.mercadopago.com.pe/checkout/v1/redirect?order_id=ORDTEST123";

        server.expect(requestTo("https://api.mercadopago.com/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(header("X-Idempotency-Key", providerKey.toString()))
                .andExpect(content().json("""
                        {
                          "type": "online",
                          "processing_mode": "manual",
                          "capture_mode": "automatic_async",
                          "total_amount": "39.90",
                          "external_reference": "%s",
                          "payer": {
                            "email": "buyer@example.test"
                          },
                          "items": [
                            {
                              "title": "Título capturado",
                              "unit_price": "39.90",
                              "quantity": 1
                            }
                          ]
                        }
                        """.formatted(attemptId)))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "id": "ORDTEST123",
                                  "status": "created",
                                  "external_reference": "%s",
                                  "currency": "PEN",
                                  "total_amount": "39.90",
                                  "checkout_url": "%s"
                                }
                                """.formatted(attemptId, checkoutUrl)));

        ProviderResult result = adapter.create(attempt);

        assertEquals("ORDTEST123", result.providerOrderId());
        assertEquals(checkoutUrl, result.checkoutUrl());
        server.verify();
    }

    @Test
    void searchesEveryPageWithoutRestrictingStatus() {
        UUID reference = UUID.randomUUID();
        Instant begin = Instant.parse("2026-11-10T00:00:00Z");
        Instant end = Instant.parse("2026-11-12T00:00:00Z");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.mercadopago.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        MercadoPagoAdapter adapter = new MercadoPagoAdapter(
                builder.build(), CircuitBreaker.ofDefaults("mercadoPago-search-test"), "test-token");

        server.expect(request -> {
                    assertEquals(HttpMethod.GET, request.getMethod());
                    assertEquals("/v1/orders", request.getURI().getPath());
                    var query = UriComponentsBuilder.fromUri(request.getURI())
                            .build()
                            .getQueryParams();
                    assertEquals(reference.toString(), query.getFirst("external_reference"));
                    assertEquals(begin.toString(), query.getFirst("begin_date"));
                    assertEquals(end.toString(), query.getFirst("end_date"));
                    assertEquals("0", query.getFirst("offset"));
                    assertEquals("20", query.getFirst("limit"));
                    assertFalse(query.containsKey("status"));
                })
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                            {
                              "data": [
                                {
                                  "id": "UNRELATED",
                                  "external_reference": "another-reference"
                                }
                              ],
                              "paging": {
                                "total": 21,
                                "offset": 0,
                                "limit": 20
                              }
                            }
                            """));

        server.expect(request -> {
                    assertEquals(HttpMethod.GET, request.getMethod());
                    assertEquals("/v1/orders", request.getURI().getPath());
                    var query = UriComponentsBuilder.fromUri(request.getURI())
                            .build()
                            .getQueryParams();
                    assertEquals(reference.toString(), query.getFirst("external_reference"));
                    assertEquals("20", query.getFirst("offset"));
                    assertEquals("20", query.getFirst("limit"));
                    assertFalse(query.containsKey("status"));
                })
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                            {
                              "data": [
                                {
                                  "id": "ORD-SECOND-PAGE",
                                  "external_reference": "%s",
                                  "status": "processed"
                                }
                              ],
                              "paging": {
                                "total": 21,
                                "offset": 20,
                                "limit": 20
                              }
                            }
                            """.formatted(reference)));

        List<String> found = adapter.findOrderIds(reference, begin, end);

        assertEquals(List.of("ORD-SECOND-PAGE"), found);
        server.verify();
    }
}
