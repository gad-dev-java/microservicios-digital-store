package com.portafolio.order_service.application.ports.out;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        UUID id,
        UUID orderId,
        String payload,
        int attempts,
        Instant leaseUntil,
        String traceparent,
        String tracestate) {}
