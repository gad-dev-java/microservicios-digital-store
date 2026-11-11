package com.portafolio.catalog_service.domain.models;

import java.math.BigDecimal;
import java.util.UUID;

public record Product(
        UUID id,
        String sku,
        String title,
        String description,
        BigDecimal price,
        String currency,
        ProductStatus status) {}
