package com.portafolio.catalog_service.application.ports.out;

import com.portafolio.catalog_service.application.ports.in.ProductPage;
import com.portafolio.catalog_service.domain.models.Product;
import java.util.UUID;
import java.util.function.Supplier;

public interface PublicCatalogCachePort {
    ProductPage page(String title, int page, int size, Supplier<ProductPage> database);

    Product product(UUID id, Supplier<Product> database);

    void invalidate();
}
