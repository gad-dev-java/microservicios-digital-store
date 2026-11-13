package com.portafolio.notification_service.infrastructure.adapters.out.persistence;

import com.portafolio.notification_service.application.ports.out.DeliveryRepositoryPort;
import com.portafolio.notification_service.domain.models.EmailDelivery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDeliveryRepositoryAdapter implements DeliveryRepositoryPort {
    private final JdbcTemplate jdbc;
    private final int maxAttempts;

    public JdbcDeliveryRepositoryAdapter(
            JdbcTemplate jdbc, @Value("${app.notifications.sender.max-attempts:5}") int maxAttempts) {
        this.jdbc = jdbc;
        this.maxAttempts = maxAttempts;
    }

    @Override
    @Transactional
    public boolean insertIfAbsent(EmailDelivery delivery) {
        return jdbc.update(
                        """
                INSERT INTO email_deliveries (
                    source_message_id, order_id, notification_type,
                    recipient_email, subject, body, traceparent, tracestate
                )
                VALUES (?, ?, 'ORDER_PAID', ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                        delivery.sourceMessageId(),
                        delivery.orderId(),
                        delivery.recipientEmail(),
                        delivery.subject(),
                        delivery.body(),
                        delivery.traceparent(),
                        delivery.tracestate())
                == 1;
    }

    @Override
    @Transactional
    public Optional<EmailDelivery> claimNext() {
        jdbc.update("""
                UPDATE email_deliveries
                SET status = 'DEAD',
                    lease_until = NULL,
                    last_error = 'LEASE_EXPIRED_AFTER_MAX_ATTEMPTS'
                WHERE status = 'IN_FLIGHT'
                  AND lease_until <= now()
                  AND attempts >= ?
                """, maxAttempts);

        List<EmailDelivery> rows = jdbc.query(
                """
                WITH candidate AS (
                    SELECT source_message_id
                    FROM email_deliveries
                    WHERE (
                        (status IN ('PENDING', 'FAILED') AND next_attempt_at <= now())
                        OR (status = 'IN_FLIGHT' AND lease_until <= now())
                    )
                      AND attempts < ?
                    ORDER BY next_attempt_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE email_deliveries AS delivery
                SET status = 'IN_FLIGHT',
                    attempts = delivery.attempts + 1,
                    lease_until = now() + interval '60 seconds'
                FROM candidate
                WHERE delivery.source_message_id = candidate.source_message_id
                RETURNING delivery.source_message_id, delivery.order_id,
                          delivery.recipient_email, delivery.subject,
                          delivery.body, delivery.traceparent,
                          delivery.tracestate, delivery.attempts
                """,
                (rs, rowNum) -> new EmailDelivery(
                        rs.getObject("source_message_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("recipient_email"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getString("traceparent"),
                        rs.getString("tracestate"),
                        rs.getInt("attempts")),
                maxAttempts);

        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public void markSent(UUID sourceMessageId, int attempts) {
        jdbc.update("""
                UPDATE email_deliveries
                SET status = 'SENT',
                    sent_at = now(),
                    lease_until = NULL,
                    last_error = NULL
                WHERE source_message_id = ?
                  AND status = 'IN_FLIGHT'
                  AND attempts = ?
                """, sourceMessageId, attempts);
    }

    @Override
    @Transactional
    public void markSendFailed(UUID sourceMessageId, int attempts) {
        long backoffSeconds = Math.min(3600L, 1L << Math.min(attempts, 12));
        jdbc.update("""
                UPDATE email_deliveries
                SET status = CASE WHEN attempts >= ? THEN 'DEAD' ELSE 'FAILED' END,
                    next_attempt_at = now() + (? * interval '1 second'),
                    lease_until = NULL,
                    last_error = 'SMTP_SEND_FAILED'
                WHERE source_message_id = ?
                  AND status = 'IN_FLIGHT'
                  AND attempts = ?
                """, maxAttempts, backoffSeconds, sourceMessageId, attempts);
    }
}
