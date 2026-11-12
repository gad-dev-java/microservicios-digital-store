package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.WebhookReceiptPort;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JdbcWebhookReceiptAdapter implements WebhookReceiptPort {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public boolean registration(String notificationId, String providerOrderId, String action, String payload) {
        jdbc.update("""
                INSERT INTO webhook_receipts
                    (id, provider_notification_id, provider_order_id,
                     action, payload, status)
                VALUES (?, ?, ?, ?, ?::jsonb, 'RECEIVED')
                ON CONFLICT (provider_notification_id) DO NOTHING
                """, UUID.randomUUID(), notificationId, providerOrderId, action, payload);

        String status = jdbc.queryForObject("""
                SELECT status
                FROM webhook_receipts
                WHERE provider_notification_id = ?
                """, String.class, notificationId);

        return "PROCESSED".equals(status);
    }

    @Override
    public void markReceived(String notificationId) {
        jdbc.update("""
                UPDATE webhook_receipts
                SET status = 'RECEIVED', last_error = NULL
                WHERE provider_notification_id = ?
                  AND status <> 'PROCESSED'
                """, notificationId);
    }

    @Override
    public void markFailed(String notificationId) {
        jdbc.update("""
                UPDATE webhook_receipts
                SET status = 'FAILED', last_error = 'PROVIDER_LOOKUP_FAILED'
                WHERE provider_notification_id = ?
                  AND status <> 'PROCESSED'
                """, notificationId);
    }
}
