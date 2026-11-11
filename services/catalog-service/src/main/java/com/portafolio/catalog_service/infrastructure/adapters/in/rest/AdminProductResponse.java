package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import com.portafolio.catalog_service.domain.models.Product;
import com.portafolio.catalog_service.domain.models.ProductStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record AdminProductResponse(
        UUID id,
        String sku,
        String title,
        String description,
        BigDecimal price,
        String currency,
        ProductStatus status,
        String fileName,
        Long fileSize) {
    public static AdminProductResponse from(Product product) {
        return new AdminProductResponse(
                product.id(),
                product.sku(),
                product.title(),
                product.description(),
                product.price(),
                product.currency(),
                product.status(),
                product.file() == null ? null : product.file().fileName(),
                product.file() == null ? null : product.file().size());
    }
}
