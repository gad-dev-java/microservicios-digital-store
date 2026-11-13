package com.portafolio.notification_service.infrastructure.adapters.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class NotificationRetentionBatch {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteSentEmails(int batchSize) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT source_message_id
                    FROM email_deliveries
                    WHERE status = 'SENT'
                      AND sent_at < now() - interval '60 days'
                    ORDER BY sent_at, source_message_id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM email_deliveries AS delivery
                USING expired
                WHERE delivery.source_message_id = expired.source_message_id
                """, batchSize);
    }
}
