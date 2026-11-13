package com.portafolio.order_service.infrastructure.adapters.out;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;
import static org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver.principal;

import com.portafolio.order_service.application.ports.in.LibraryUseCase.Download;
import com.portafolio.order_service.application.ports.out.CatalogDownloadPort;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestCatalogDownloadAdapter implements CatalogDownloadPort {
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private final RestClient client;

    public RestCatalogDownloadAdapter(@Qualifier("catalogDownloadRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public Download metadata(UUID productId) {
        return client.head()
                .uri("/api/internal/catalog/{id}/download", productId)
                .attributes(clientRegistrationId("order-catalog"))
                .attributes(principal("order-service"))
                .exchange((request, response) -> {
                    requireOk(response.getStatusCode());

                    var headers = response.getHeaders();
                    long size = headers.getContentLength();
                    String name = headers.getContentDisposition().getFilename();
                    var type = headers.getContentType();

                    if (size < 1 || size > MAX_BYTES || name == null || type == null) {
                        throw unavailable();
                    }
                    return new Download(name, type.toString(), size);
                });
    }

    @Override
    public void stream(UUID productId, long expectedSize, OutputStream output) throws IOException {
        client.get()
                .uri("/api/internal/catalog/{id}/download", productId)
                .attributes(clientRegistrationId("order-catalog"))
                .attributes(principal("order-service"))
                .exchange((request, response) -> {
                    requireOk(response.getStatusCode());
                    if (response.getHeaders().getContentLength() != expectedSize) {
                        throw unavailable();
                    }

                    try (var input = response.getBody()) {
                        byte[] buffer = new byte[8192];
                        long copied = 0;
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            copied += read;
                            if (copied > expectedSize || copied > MAX_BYTES) {
                                throw new IOException("Descarga supera el límite");
                            }
                            output.write(buffer, 0, read);
                        }
                        if (copied != expectedSize) {
                            throw new IOException("Descarga incompleta");
                        }
                    }
                    return null;
                });
    }

    private static void requireOk(HttpStatusCode status) {
        if (!status.is2xxSuccessful()) {
            throw unavailable();
        }
    }

    private static OrderException unavailable() {
        return new OrderException(Reason.CATALOG_UNAVAILABLE, "Archivo no disponible en Catalog");
    }
}
