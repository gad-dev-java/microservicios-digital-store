package com.portafolio.notification_service.domain.models;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderPaidEvent(
        UUID eventId,
        UUID orderId,
        String buyerEmail,
        BigDecimal amount,
        String currency,
        List<String> titles,
        String traceparent,
        String tracestate) {}
