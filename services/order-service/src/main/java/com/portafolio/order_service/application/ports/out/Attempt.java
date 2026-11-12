package com.portafolio.order_service.application.ports.out;

import java.math.BigDecimal;
import java.util.UUID;

public record Attempt(
        UUID id,
        UUID orderId,
        String providerOrderId,
        BigDecimal amount,
        String currency,
        String status,
        String reviewReason) {}
