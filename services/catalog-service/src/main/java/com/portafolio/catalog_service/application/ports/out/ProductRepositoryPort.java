package com.portafolio.catalog_service.application.ports.out;

import com.portafolio.catalog_service.application.ports.in.ProductPage;
import com.portafolio.catalog_service.domain.models.Product;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepositoryPort {
    ProductPage searchPublished(String title, int page, int size);

    Optional<Product> findById(UUID id);
}
