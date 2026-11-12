package com.portafolio.order_service.infrastructure.adapters.in.rest;

import java.math.BigDecimal;
import java.util.UUID;

public record ItemResponse(UUID productId, String sku, String title, BigDecimal unitPrice) {}
