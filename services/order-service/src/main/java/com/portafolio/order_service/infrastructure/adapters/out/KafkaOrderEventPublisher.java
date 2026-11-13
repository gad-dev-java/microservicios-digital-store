package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.OrderEventPublisherPort;
import com.portafolio.order_service.application.ports.out.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOrderEventPublisher implements OrderEventPublisherPort {
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int ackTimeoutSeconds;

    public KafkaOrderEventPublisher(
            KafkaTemplate<String, String> kafka,
            @Value("${outbox.relay.topic:orders.events.v1}") String topic,
            @Value("${outbox.relay.ack-timeout-seconds:10}") int ackTimeoutSeconds) {
        this.kafka = kafka;
        this.topic = topic;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
    }

    @Override
    public void publish(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, event.orderId().toString(), event.payload());

        addHeader(record, "eventId", event.id().toString());
        addHeader(record, "traceparent", event.traceparent());
        addHeader(record, "tracestate", event.tracestate());

        try {
            kafka.send(record).get(ackTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicación interrumpida", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Kafka no confirmó el evento", failure);
        }
    }

    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        if (value != null && !value.isBlank()) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
