package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.out.OrderEventPublisherPort;
import com.portafolio.order_service.application.ports.out.OutboxEvent;
import com.portafolio.order_service.application.ports.out.OutboxStorePort;
import com.portafolio.order_service.application.ports.out.TraceContextPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PublishOutboxService {
    private final OutboxStorePort store;
    private final OrderEventPublisherPort publisher;
    private final int maxAttempts;
    private final int batchSize;
    private final int leaseSeconds;
    private final TraceContextPort traceContext;

    public PublishOutboxService(
            OutboxStorePort store,
            OrderEventPublisherPort publisher,
            @Value("${outbox.relay.max-attempts:5}") int maxAttempts,
            @Value("${outbox.relay.batch-size:20}") int batchSize,
            @Value("${outbox.relay.lease-seconds:30}") int leaseSeconds,
            TraceContextPort traceContext) {
        this.store = store;
        this.publisher = publisher;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.traceContext = traceContext;
    }

    // Deliberadamente sin @Transactional: nunca esperar Kafka con
    // una transacción de PostgreSQL abierta.
    public void runBatch() {
        for (OutboxEvent event : store.claimDue(batchSize, leaseSeconds)) {
            traceContext.runWithParent(
                    event.traceparent(), event.tracestate(), "outbox.publish", () -> publishOne(event));
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            publisher.publish(event);
            if (store.markPublished(event)) {
                log.info("Evento publicado: eventId={}, orderId={}", event.id(), event.orderId());
            } else {
                log.warn("El lease del outbox cambió antes de confirmar: eventId={}", event.id());
            }
        } catch (RuntimeException failure) {
            boolean dead = event.attempts() >= maxAttempts;
            int delay = retryDelay(event.attempts());
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            String errorCode = cause.getClass().getSimpleName();

            if (!store.markFailed(event, dead, delay, errorCode)) {
                log.warn("El lease del outbox cambió tras el fallo: eventId={}", event.id());
            } else if (dead) {
                log.error("Evento outbox requiere revisión: eventId={}, intentos={}", event.id(), event.attempts());
            } else {
                log.warn("Evento outbox pendiente de reintento: eventId={}, intentos={}", event.id(), event.attempts());
            }
        }
    }

    private int retryDelay(int attempts) {
        return (int) Math.min(3600L, 10L * (1L << Math.min(attempts - 1, 8)));
    }
}
