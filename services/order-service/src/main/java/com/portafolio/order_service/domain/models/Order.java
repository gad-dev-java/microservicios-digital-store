package com.portafolio.order_service.domain.models;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record Order(
        UUID id,
        String buyerSub,
        String buyerEmail,
        List<OrderItem> items,
        BigDecimal totalAmount,
        String currency,
        Status status) {
    public Order {
        items = List.copyOf(items);
    }

    public static Order create(String buyerSub, String buyerEmail, List<OrderItem> items) {
        BigDecimal total = items.stream().map(OrderItem::unitPrice).reduce(BigDecimal.ZERO, BigDecimal::add);

        if (items.isEmpty() || total.signum() <= 0 || total.precision() - total.scale() > 10 || total.scale() > 2) {
            throw new IllegalArgumentException("Importe del pedido inválido");
        }

        return new Order(UUID.randomUUID(), buyerSub, buyerEmail, items, total, "PEN", Status.CREATED);
    }
}
