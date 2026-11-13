package com.portafolio.notification_service.domain.models;

import java.util.UUID;

public record EmailDelivery(
        UUID sourceMessageId,
        UUID orderId,
        String recipientEmail,
        String subject,
        String body,
        String traceparent,
        String tracestate,
        int attempts) {}
