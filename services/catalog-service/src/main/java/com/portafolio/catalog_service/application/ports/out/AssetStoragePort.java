package com.portafolio.catalog_service.application.ports.out;

import com.portafolio.catalog_service.domain.models.AssetMetadata;
import java.io.InputStream;

public interface AssetStoragePort {
    AssetMetadata store(InputStream source, String originalName, String contentType, long expectedSize);

    boolean matches(AssetMetadata metadata);

    void delete(String key);
}
