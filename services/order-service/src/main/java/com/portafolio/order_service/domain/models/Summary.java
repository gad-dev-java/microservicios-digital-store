package com.portafolio.order_service.domain.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Summary(
        UUID id,
        String buyerEmail,
        Status status,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        Instant paidAt,
        String paymentStatus,
        String reviewReason) {}
