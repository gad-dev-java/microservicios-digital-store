package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import com.portafolio.catalog_service.application.ports.in.ReadPurchasedFileUseCase;
import com.portafolio.catalog_service.domain.models.AssetMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/internal/catalog")
public class InternalDownloadController {
    private final ReadPurchasedFileUseCase files;

    public InternalDownloadController(ReadPurchasedFileUseCase files) {
        this.files = files;
    }

    @RequestMapping(path = "/{id}/download", method = RequestMethod.HEAD)
    public ResponseEntity<Void> metadata(@PathVariable UUID id) {
        return headers(files.metadata(id)).build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id) {
        AssetMetadata file = files.metadata(id);

        StreamingResponseBody body = output -> {
            try (InputStream input = files.open(file)) {
                byte[] buffer = new byte[8192];
                long copied = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    copied += read;
                    if (copied > file.size()) {
                        throw new IOException("El archivo supera el tamaño registrado");
                    }
                    output.write(buffer, 0, read);
                }
                if (copied != file.size()) {
                    throw new IOException("El archivo está incompleto");
                }
            }
        };

        return headers(file).body(body);
    }

    private static ResponseEntity.BodyBuilder headers(AssetMetadata file) {
        String safeName = file.fileName().replaceAll("[\\r\\n\\\\/\";]", "_");
        String disposition = ContentDisposition.attachment()
                .filename(safeName, StandardCharsets.UTF_8)
                .build()
                .toString();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff");
    }
}
