package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.domain.models.Summary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SummaryResponse(
        UUID id,
        String buyerEmail,
        String status,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        Instant paidAt,
        String paymentStatus,
        String reviewReason) {
    static SummaryResponse from(Summary source) {
        return new SummaryResponse(
                source.id(),
                source.buyerEmail(),
                source.status().name(),
                source.totalAmount(),
                source.currency(),
                source.createdAt(),
                source.paidAt(),
                source.paymentStatus(),
                source.reviewReason());
    }
}
