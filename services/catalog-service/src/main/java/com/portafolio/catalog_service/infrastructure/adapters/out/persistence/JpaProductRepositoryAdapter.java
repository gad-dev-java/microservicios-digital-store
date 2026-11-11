package com.portafolio.catalog_service.infrastructure.adapters.out.persistence;

import com.portafolio.catalog_service.application.ports.in.ProductPage;
import com.portafolio.catalog_service.application.ports.out.ProductRepositoryPort;
import com.portafolio.catalog_service.domain.models.AssetMetadata;
import com.portafolio.catalog_service.domain.models.Product;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaProductRepositoryAdapter implements ProductRepositoryPort {
    private final SpringDataProductRepository productRepository;

    @Override
    public ProductPage searchPublished(String title, int page, int size) {
        String scaped = title.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");

        String pattern = "%" + scaped + "%";

        Page<Product> result = productRepository
                .searchPublished(pattern, PageRequest.of(page, size))
                .map(JpaProductRepositoryAdapter::toDomain);

        return new ProductPage(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return productRepository.findById(id).map(JpaProductRepositoryAdapter::toDomain);
    }

    @Override
    public ProductPage listAll(int page, int size) {
        Page<Product> result = productRepository
                .findAll(PageRequest.of(
                        page,
                        size,
                        Sort.by("title").ascending().and(Sort.by("id").ascending())))
                .map(JpaProductRepositoryAdapter::toDomain);

        return new ProductPage(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Override
    public Product save(Product product) {
        return toDomain(productRepository.saveAndFlush(new ProductEntity(product)));
    }

    private static Product toDomain(ProductEntity entity) {
        AssetMetadata file = entity.getStorageKey() == null
                ? null
                : new AssetMetadata(
                        entity.getStorageKey(),
                        entity.getFileName(),
                        entity.getContentType(),
                        entity.getFileSize(),
                        entity.getChecksumSha256());

        return new Product(
                entity.getId(),
                entity.getSku(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getPrice(),
                entity.getCurrency(),
                entity.getStatus(),
                file);
    }
}
