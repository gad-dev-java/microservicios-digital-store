package com.portafolio.order_service.application.ports.out;

import com.portafolio.order_service.application.ports.in.LibraryUseCase.Download;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public interface CatalogDownloadPort {
    Download metadata(UUID productId);

    void stream(UUID productId, long expectedSize, OutputStream output) throws IOException;
}
