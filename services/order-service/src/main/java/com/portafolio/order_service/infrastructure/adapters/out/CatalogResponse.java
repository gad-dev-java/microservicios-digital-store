package com.portafolio.order_service.infrastructure.adapters.out;

import java.math.BigDecimal;
import java.util.UUID;

public record CatalogResponse(UUID id, String sku, String title, BigDecimal price, String currency) {}
