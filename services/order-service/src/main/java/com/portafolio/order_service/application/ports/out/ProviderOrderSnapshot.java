package com.portafolio.order_service.application.ports.out;

import java.math.BigDecimal;

public record ProviderOrderSnapshot(
        String id,
        String externalReference,
        BigDecimal totalAmount,
        BigDecimal totalPaidAmount,
        String currency,
        String status,
        String statusDetail,
        Boolean liveMode,
        String applicationId) {}
