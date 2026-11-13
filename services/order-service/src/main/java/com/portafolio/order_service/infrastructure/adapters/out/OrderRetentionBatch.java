package com.portafolio.order_service.infrastructure.adapters.out;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class OrderRetentionBatch {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteProcessedWebhooks(int batchSize) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT id
                    FROM webhook_receipts
                    WHERE status = 'PROCESSED'
                      AND processed_at < now() - interval '30 days'
                    ORDER BY processed_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM webhook_receipts AS receipt
                USING expired
                WHERE receipt.id = expired.id
                """, batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deletePublishedOutbox(int batchSize) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT id
                    FROM outbox_events
                    WHERE status = 'PUBLISHED'
                      AND published_at < now() - interval '30 days'
                    ORDER BY published_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM outbox_events AS event
                USING expired
                WHERE event.id = expired.id
                """, batchSize);
    }
}
