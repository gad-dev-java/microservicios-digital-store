package com.portafolio.catalog_service.domain.models;

import com.portafolio.catalog_service.domain.exceptions.InvalidProductException;
import com.portafolio.catalog_service.domain.exceptions.InvalidProductStateException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

public record Product(
        UUID id,
        String sku,
        String title,
        String description,
        BigDecimal price,
        String currency,
        ProductStatus status,
        AssetMetadata file) {

    public static Product draft(String sku, String title, String description, BigDecimal price) {
        if (sku == null || title == null || description == null || price == null) {
            throw new InvalidProductException("Faltan datos del producto");
        }

        String normalizedSku = sku.trim().toUpperCase(Locale.ROOT);
        String normalizedTitle = title.trim();
        String normalizedDescription = description.trim();

        if (!normalizedSku.matches("[A-Z0-9][A-Z0-9_-]{0,63}")
                || normalizedTitle.isBlank()
                || normalizedTitle.length() > 200
                || normalizedDescription.isBlank()
                || price.signum() <= 0
                || price.scale() > 2
                || price.precision() - price.scale() > 10) {
            throw new InvalidProductException("SKU, título, descripción o precio inválidos");
        }

        return new Product(
                UUID.randomUUID(),
                normalizedSku,
                normalizedTitle,
                normalizedDescription,
                price,
                "PEN",
                ProductStatus.DRAFT,
                null);
    }

    public Product withFile(AssetMetadata asset) {
        if (status != ProductStatus.DRAFT || file != null) {
            throw new InvalidProductStateException("Solo un borrador sin archivo admite una carga");
        }
        return new Product(id, sku, title, description, price, currency, status, asset);
    }

    public Product publish() {
        if (status != ProductStatus.DRAFT || file == null) {
            throw new InvalidProductStateException("El borrador necesita un archivo completo para publicarse");
        }
        return new Product(id, sku, title, description, price, currency, ProductStatus.PUBLISHED, file);
    }

    public Product archive() {
        if (status != ProductStatus.PUBLISHED) {
            throw new InvalidProductStateException("Solo un producto publicado puede archivarse");
        }
        return new Product(id, sku, title, description, price, currency, ProductStatus.ARCHIVED, file);
    }

    public Product updateDetails(String title, String description, BigDecimal price) {
        if (status == ProductStatus.ARCHIVED) {
            throw new InvalidProductStateException("No se puede editar un producto archivado");
        }

        Product validated = Product.draft(sku, title, description, price);
        return new Product(
                id, sku, validated.title(), validated.description(), validated.price(), currency, status, file);
    }
}
