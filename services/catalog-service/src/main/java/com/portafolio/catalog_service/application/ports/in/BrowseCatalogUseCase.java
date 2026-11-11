package com.portafolio.catalog_service.application.ports.in;

import com.portafolio.catalog_service.domain.models.Product;
import java.util.UUID;

public interface BrowseCatalogUseCase {
    ProductPage search(String title, int page, int size);

    Product getPublished(UUID id);
}
