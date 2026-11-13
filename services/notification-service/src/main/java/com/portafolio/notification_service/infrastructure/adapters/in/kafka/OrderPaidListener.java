package com.portafolio.notification_service.infrastructure.adapters.in.kafka;

import com.portafolio.notification_service.application.ports.in.HandleOrderPaidUseCase;
import com.portafolio.notification_service.domain.models.OrderPaidEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderPaidListener {
    private final ObjectMapper json;
    private final HandleOrderPaidUseCase useCase;

    public OrderPaidListener(ObjectMapper json, HandleOrderPaidUseCase useCase) {
        this.json = json;
        this.useCase = useCase;
    }

    @KafkaListener(topics = "orders.events.v1")
    public void receive(ConsumerRecord<String, String> record) {
        try {
            JsonNode root = json.readTree(record.value());
            if (root == null
                    || !root.isObject()
                    || !"ORDER_PAID".equals(root.path("eventType").asText())
                    || root.path("schemaVersion").asInt() != 1
                    || !root.path("amount").isNumber()
                    || !root.path("items").isArray()) {
                throw new InvalidOrderPaidEventException();
            }

            UUID eventId = UUID.fromString(root.path("eventId").asText());
            UUID orderId = UUID.fromString(root.path("orderId").asText());
            String email = root.path("buyerEmail").asText();
            String currency = root.path("currency").asText();
            BigDecimal amount = root.path("amount").decimalValue();

            if (email.isBlank() || !currency.matches("[A-Z]{3}") || amount.signum() <= 0) {
                throw new InvalidOrderPaidEventException();
            }

            List<String> titles = new ArrayList<>();
            for (JsonNode item : root.path("items")) {
                String title = item.path("title").asText();
                if (title.isBlank()) {
                    throw new InvalidOrderPaidEventException();
                }
                titles.add(title);
            }
            if (titles.isEmpty()) {
                throw new InvalidOrderPaidEventException();
            }

            useCase.handle(new OrderPaidEvent(
                    eventId,
                    orderId,
                    email,
                    amount,
                    currency,
                    titles,
                    header(record, "traceparent"),
                    header(record, "tracestate")));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidOrderPaidEventException(exception);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
