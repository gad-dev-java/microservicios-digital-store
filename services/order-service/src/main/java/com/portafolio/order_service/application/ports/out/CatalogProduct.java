package com.portafolio.order_service.application.ports.out;

import java.math.BigDecimal;
import java.util.UUID;

public record CatalogProduct(UUID id, String sku, String title, BigDecimal price, String currency) {}
