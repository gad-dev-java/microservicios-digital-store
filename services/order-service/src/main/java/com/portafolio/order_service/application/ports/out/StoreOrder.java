package com.portafolio.order_service.application.ports.out;

import java.math.BigDecimal;
import java.util.UUID;

public record StoreOrder(
        UUID id,
        String buyerSub,
        String buyerEmail,
        BigDecimal totalAmount,
        String currency,
        String status,
        int itemCount) {}
