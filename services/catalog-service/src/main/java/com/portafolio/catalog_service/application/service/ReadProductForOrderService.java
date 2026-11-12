package com.portafolio.catalog_service.application.service;

import com.portafolio.catalog_service.application.ports.in.ReadProductForOrderUseCase;
import com.portafolio.catalog_service.application.ports.out.ProductRepositoryPort;
import com.portafolio.catalog_service.domain.exceptions.ProductNotFoundException;
import com.portafolio.catalog_service.domain.models.Product;
import com.portafolio.catalog_service.domain.models.ProductStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReadProductForOrderService implements ReadProductForOrderUseCase {
    private final ProductRepositoryPort products;

    @Override
    public Product getPublished(UUID id) {
        return products.findById(id)
                .filter(product -> product.status() == ProductStatus.PUBLISHED)
                .orElseThrow(ProductNotFoundException::new);
    }
}
