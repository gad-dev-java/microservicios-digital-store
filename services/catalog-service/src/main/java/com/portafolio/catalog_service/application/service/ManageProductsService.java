package com.portafolio.catalog_service.application.service;

import com.portafolio.catalog_service.application.ports.in.ManageProductsUseCase;
import com.portafolio.catalog_service.application.ports.in.ProductPage;
import com.portafolio.catalog_service.application.ports.out.AssetStoragePort;
import com.portafolio.catalog_service.application.ports.out.ProductRepositoryPort;
import com.portafolio.catalog_service.domain.exceptions.InvalidProductStateException;
import com.portafolio.catalog_service.domain.exceptions.ProductNotFoundException;
import com.portafolio.catalog_service.domain.models.AssetMetadata;
import com.portafolio.catalog_service.domain.models.Product;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional
@RequiredArgsConstructor
public class ManageProductsService implements ManageProductsUseCase {
    private final ProductRepositoryPort products;
    private final AssetStoragePort files;

    @Transactional(readOnly = true)
    @Override
    public ProductPage list(int page, int size) {
        return products.listAll(page, size);
    }

    @Transactional(readOnly = true)
    @Override
    public Product get(UUID id) {
        return products.findById(id).orElseThrow(ProductNotFoundException::new);
    }

    @Override
    public Product createDraft(String sku, String title, String description, BigDecimal price) {
        return products.save(Product.draft(sku, title, description, price));
    }

    @Override
    public Product upload(UUID id, InputStream source, String originalName, String contentType, long expectedSize) {
        Product product = get(id);
        if (product.file() != null) {
            throw new InvalidProductStateException("El producto ya tiene un archivo");
        }

        // Valida el estado antes de escribir en disco.
        if (product.status() != com.portafolio.catalog_service.domain.models.ProductStatus.DRAFT) {
            throw new InvalidProductStateException("Solo un borrador admite un archivo");
        }

        AssetMetadata stored = files.store(source, originalName, contentType, expectedSize);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    files.delete(stored.key());
                }
            }
        });

        return products.save(product.withFile(stored));
    }

    @Override
    public Product publish(UUID id) {
        Product product = get(id);
        Product published = product.publish();
        if (!files.matches(published.file())) {
            throw new InvalidProductStateException("El archivo almacenado falta o no coincide con sus metadatos");
        }
        return products.save(published);
    }

    @Override
    public Product archive(UUID id) {
        return products.save(get(id).archive());
    }
}
