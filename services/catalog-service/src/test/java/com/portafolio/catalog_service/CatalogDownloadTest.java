package com.portafolio.catalog_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portafolio.catalog_service.application.ports.in.ReadPurchasedFileUseCase;
import com.portafolio.catalog_service.application.ports.out.AssetStoragePort;
import com.portafolio.catalog_service.application.ports.out.ProductRepositoryPort;
import com.portafolio.catalog_service.application.service.ReadPurchasedFileService;
import com.portafolio.catalog_service.domain.models.AssetMetadata;
import com.portafolio.catalog_service.domain.models.Product;
import com.portafolio.catalog_service.domain.models.ProductStatus;
import com.portafolio.catalog_service.infrastructure.adapters.in.rest.InternalDownloadController;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class CatalogDownloadTest {
    private static final int FILE_SIZE = 50 * 1024 * 1024;

    @Test
    void archivedProductKeepsItsFileAvailableToInternalUseCase() {
        UUID productId = UUID.randomUUID();
        AssetMetadata file = metadata(16);

        Product product = new Product(
                productId,
                "JAVA-ARCHIVED",
                "Curso archivado",
                "Contenido",
                new BigDecimal("12.00"),
                "PEN",
                ProductStatus.ARCHIVED,
                file);

        ProductRepositoryPort products = mock(ProductRepositoryPort.class);
        AssetStoragePort storage = mock(AssetStoragePort.class);
        when(products.findById(productId)).thenReturn(Optional.of(product));

        ReadPurchasedFileService service = new ReadPurchasedFileService(products, storage);

        assertEquals(file, service.metadata(productId));
    }

    @Test
    void largeFileIsReadInSmallBlocks() throws Exception {
        UUID productId = UUID.randomUUID();
        AssetMetadata file = metadata(FILE_SIZE);
        ReadPurchasedFileUseCase files = mock(ReadPurchasedFileUseCase.class);
        CountingInputStream input = new CountingInputStream(FILE_SIZE);

        when(files.metadata(productId)).thenReturn(file);
        when(files.open(file)).thenReturn(input);

        var response = new InternalDownloadController(files).download(productId);
        assertNotNull(response.getBody());
        assertEquals(FILE_SIZE, response.getHeaders().getContentLength());

        response.getBody().writeTo(OutputStream.nullOutputStream());

        assertEquals(FILE_SIZE, input.totalRead);
        assertTrue(input.largestRequestedRead <= 8192);
        assertTrue(input.closed);
    }

    @Test
    void contentDispositionRemovesPathSeparatorsAndLineBreaks() {
        UUID productId = UUID.randomUUID();
        ReadPurchasedFileUseCase files = mock(ReadPurchasedFileUseCase.class);
        AssetMetadata file = new AssetMetadata(
                UUID.randomUUID().toString(), "../privado\r\n.pdf", "application/pdf", 16, "a".repeat(64));
        when(files.metadata(productId)).thenReturn(file);

        var response = new InternalDownloadController(files).metadata(productId);
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);

        assertNotNull(disposition);
        assertTrue(disposition.startsWith("attachment"));
        assertTrue(!disposition.contains("/") && !disposition.contains("\r") && !disposition.contains("\n"));
    }

    private static AssetMetadata metadata(long size) {
        return new AssetMetadata(UUID.randomUUID().toString(), "guia.pdf", "application/pdf", size, "a".repeat(64));
    }

    private static final class CountingInputStream extends InputStream {
        private long remaining;
        private long totalRead;
        private int largestRequestedRead;
        private boolean closed;

        private CountingInputStream(long size) {
            remaining = size;
        }

        @Override
        public int read() {
            if (remaining == 0) return -1;
            remaining--;
            totalRead++;
            return 0;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (remaining == 0) return -1;
            largestRequestedRead = Math.max(largestRequestedRead, length);
            int count = (int) Math.min(remaining, length);
            remaining -= count;
            totalRead += count;
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
