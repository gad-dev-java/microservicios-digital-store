package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.domain.models.Status;
import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutOrderRow(UUID id, String buyerEmail, BigDecimal amount, String currency, Status status) {}
