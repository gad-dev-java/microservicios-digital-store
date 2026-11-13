package com.portafolio.catalog_service.application.ports.in;

import com.portafolio.catalog_service.domain.models.AssetMetadata;
import java.io.InputStream;
import java.util.UUID;

public interface ReadPurchasedFileUseCase {
    AssetMetadata metadata(UUID productId);

    InputStream open(AssetMetadata metadata);
}
