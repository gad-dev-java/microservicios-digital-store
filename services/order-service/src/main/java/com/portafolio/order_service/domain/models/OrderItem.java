package com.portafolio.order_service.domain.models;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItem(UUID id, UUID productId, String sku, String title, BigDecimal unitPrice) {}
