package com.portafolio.catalog_service.application.service;

import com.portafolio.catalog_service.application.ports.in.ReadPurchasedFileUseCase;
import com.portafolio.catalog_service.application.ports.out.AssetStorageException;
import com.portafolio.catalog_service.application.ports.out.AssetStoragePort;
import com.portafolio.catalog_service.application.ports.out.ProductRepositoryPort;
import com.portafolio.catalog_service.domain.exceptions.ProductNotFoundException;
import com.portafolio.catalog_service.domain.models.AssetMetadata;
import com.portafolio.catalog_service.domain.models.Product;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReadPurchasedFileService implements ReadPurchasedFileUseCase {
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final ProductRepositoryPort products;
    private final AssetStoragePort storage;

    public ReadPurchasedFileService(ProductRepositoryPort products, AssetStoragePort storage) {
        this.products = products;
        this.storage = storage;
    }

    @Override
    public AssetMetadata metadata(UUID productId) {
        Product product = products.findById(productId).orElseThrow(ProductNotFoundException::new);
        AssetMetadata file = product.file();
        if (file == null) {
            throw new ProductNotFoundException();
        }
        if (file.size() < 1 || file.size() > MAX_BYTES) {
            throw new AssetStorageException("Tamaño de archivo inválido");
        }
        return file;
    }

    @Override
    public InputStream open(AssetMetadata metadata) {
        return storage.open(metadata.key());
    }
}
