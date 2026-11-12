package com.portafolio.order_service.domain.models;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record Attempt(
        UUID id,
        UUID orderId,
        UUID providerIdempotencyKey,
        BigDecimal amount,
        String currency,
        String buyerEmail,
        List<OrderItem> items) {
    public Attempt {
        items = List.copyOf(items);
    }
}
