package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.application.ports.in.LibraryUseCase;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/library")
public class LibraryController {
    private final LibraryUseCase library;
    private final Semaphore downloadSlots = new Semaphore(4);

    public LibraryController(LibraryUseCase library) {
        this.library = library;
    }

    @GetMapping
    public LibraryUseCase.Page list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return library.list(jwt.getSubject(), page, size);
    }

    @GetMapping("/{productId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        String buyerSub = jwt.getSubject();
        LibraryUseCase.Download file = library.prepare(buyerSub, productId);

        if (!downloadSlots.tryAcquire()) {
            throw new OrderException(Reason.DOWNLOAD_BUSY, "Hay demasiadas descargas simultáneas");
        }

        String safeName = file.fileName().replaceAll("[\\r\\n\\\\/\";]", "_");
        String disposition = ContentDisposition.attachment()
                .filename(safeName, StandardCharsets.UTF_8)
                .build()
                .toString();

        StreamingResponseBody body = output -> {
            try {
                library.stream(buyerSub, productId, file.size(), output);
            } finally {
                downloadSlots.release();
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
