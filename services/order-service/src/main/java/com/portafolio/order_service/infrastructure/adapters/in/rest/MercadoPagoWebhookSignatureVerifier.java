package com.portafolio.order_service.infrastructure.adapters.in.rest;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MercadoPagoWebhookSignatureVerifier {
    private final String secret;

    public MercadoPagoWebhookSignatureVerifier(@Value("${MERCADOPAGO_WEBHOOK_SECRET:}") String secret) {
        this.secret = secret;
    }

    public boolean isValid(String signature, String requestId, String dataId) {
        if (secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook secret no configurado");
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }

        String ts = null;
        String v1 = null;

        for (String part : signature.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                return false;
            }
            if ("ts".equals(pair[0])) {
                if (ts != null) return false;
                ts = pair[1];
            } else if ("v1".equals(pair[0])) {
                if (v1 != null) return false;
                v1 = pair[1];
            }
        }

        if (ts == null || !ts.matches("\\d{10,13}") || v1 == null || !v1.matches("[0-9a-fA-F]{64}")) {
            return false;
        }

        StringBuilder manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            manifest.append("id:").append(dataId.toLowerCase(Locale.ROOT)).append(';');
        }
        if (requestId != null && !requestId.isBlank()) {
            manifest.append("request-id:").append(requestId).append(';');
        }
        manifest.append("ts:").append(ts).append(';');

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(manifest.toString().getBytes(StandardCharsets.UTF_8));
            byte[] received = HexFormat.of().parseHex(v1);
            return MessageDigest.isEqual(expected, received);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 no disponible", exception);
        }
    }
}
