package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.application.ports.in.ProcessWebhookUseCase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequiredArgsConstructor
public class MercadoPagoWebhookController {
    private final MercadoPagoWebhookSignatureVerifier verifier;
    private final ProcessWebhookUseCase processWebhook;
    private final ObjectMapper objectMapper;

    @PostMapping("/api/webhooks/mercadopago")
    @ResponseStatus(HttpStatus.OK)
    public void receive(
            @RequestParam(name = "data.id", required = false) String dataId,
            @RequestHeader(name = "x-signature", required = false) String signature,
            @RequestHeader(name = "x-request-id", required = false) String requestId,
            @RequestBody(required = false) String payload) {
        if (!verifier.isValid(signature, requestId, dataId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Firma inválida");
        }

        if (dataId == null || !dataId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el ID de la orden");
        }

        JsonNode body;
        try {
            body = objectMapper.readTree(payload == null ? "" : payload);
        } catch (JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON inválido");
        }

        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notificación inválida");
        }

        if (!"order".equals(body.path("type").asText())) {
            return;
        }

        String notificationId = body.path("id").asText("");
        String bodyOrderId = body.path("data").path("id").asText("");
        String action = body.path("action").asText("");

        if (notificationId.isBlank()) {
            String createdAt = body.path("date_created").asText("");
            if (createdAt.isBlank() || action.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notificación sin identificador estable");
            }
            notificationId = "order:" + sha256(dataId + "|" + action + "|" + createdAt);
        }

        if (notificationId.isBlank()
                || notificationId.length() > 128
                || !dataId.equalsIgnoreCase(bodyOrderId)
                || action.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Notificación inválida");
        }

        JsonNode liveModeNode = body.path("live_mode");
        Boolean notificationLiveMode = liveModeNode.isBoolean() ? liveModeNode.asBoolean() : null;

        processWebhook.process(notificationId, dataId, action, payload, notificationLiveMode);
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
