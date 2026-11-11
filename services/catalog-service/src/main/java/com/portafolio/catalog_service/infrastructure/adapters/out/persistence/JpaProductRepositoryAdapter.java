package com.portafolio.catalog_service.infrastructure.adapters.out.persistence;

import com.portafolio.catalog_service.application.ports.in.ProductPage;
import com.portafolio.catalog_service.application.ports.out.ProductRepositoryPort;
import com.portafolio.catalog_service.domain.models.Product;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    private static Product toDomain(ProductEntity entity) {
        return new Product(
                entity.getId(),
                entity.getSku(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getPrice(),
                entity.getCurrency(),
                entity.getStatus());
    }
}
