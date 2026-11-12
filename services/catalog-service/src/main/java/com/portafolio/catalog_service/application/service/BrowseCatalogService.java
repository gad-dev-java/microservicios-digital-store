package com.portafolio.catalog_service.application.service;

import com.portafolio.catalog_service.application.ports.in.BrowseCatalogUseCase;
import com.portafolio.catalog_service.application.ports.in.ProductPage;
import com.portafolio.catalog_service.application.ports.out.ProductRepositoryPort;
import com.portafolio.catalog_service.application.ports.out.PublicCatalogCachePort;
import com.portafolio.catalog_service.domain.exceptions.ProductNotFoundException;
import com.portafolio.catalog_service.domain.models.Product;
import com.portafolio.catalog_service.domain.models.ProductStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BrowseCatalogService implements BrowseCatalogUseCase {
    private final ProductRepositoryPort productsRepository;
    private final PublicCatalogCachePort cache;

    @Override
    public ProductPage search(String title, int page, int size) {
        return cache.page(title, page, size, () -> productsRepository.searchPublished(title, page, size));
    }

    @Override
    public Product getPublished(UUID id) {
        return cache.product(
                id,
                () -> productsRepository
                        .findById(id)
                        .filter(product -> product.status() == ProductStatus.PUBLISHED)
                        .orElseThrow(ProductNotFoundException::new));
    }
}
