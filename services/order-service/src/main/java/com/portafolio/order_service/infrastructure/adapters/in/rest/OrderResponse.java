package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.domain.models.Order;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID id, String status, BigDecimal totalAmount, String currency, List<ItemResponse> items) {
    static OrderResponse from(Order order) {
        return new OrderResponse(
                order.id(),
                order.status().name(),
                order.totalAmount(),
                order.currency(),
                order.items().stream()
                        .map(item -> new ItemResponse(item.productId(), item.sku(), item.title(), item.unitPrice()))
                        .toList());
    }
}
