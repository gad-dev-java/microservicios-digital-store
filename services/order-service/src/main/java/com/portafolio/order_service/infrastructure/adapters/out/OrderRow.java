package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.domain.models.Status;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderRow(
        String hash, UUID id, String buyerSub, String buyerEmail, BigDecimal total, String currency, Status status) {}
