package com.portafolio.order_service.application.ports.out;

import java.time.Instant;
import java.util.UUID;

public record ReviewAttempt(
        UUID id,
        UUID orderId,
        String providerOrderId,
        String status,
        String providerStatus,
        String providerStatusDetail,
        String reviewReason,
        String resolutionReason,
        String resolvedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt) {}
