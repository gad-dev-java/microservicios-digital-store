package com.portafolio.catalog_service.application.ports.in;

import com.portafolio.catalog_service.domain.models.Product;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.UUID;

public interface ManageProductsUseCase {
    ProductPage list(int page, int size);

    Product get(UUID id);

    Product createDraft(String sku, String title, String description, BigDecimal price);

    Product upload(UUID id, InputStream source, String originalName, String contentType, long expectedSize);

    Product publish(UUID id);

    Product archive(UUID id);
}
