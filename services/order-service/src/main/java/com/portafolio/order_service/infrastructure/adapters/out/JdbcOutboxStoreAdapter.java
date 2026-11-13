package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.OutboxEvent;
import com.portafolio.order_service.application.ports.out.OutboxStorePort;
import java.sql.Timestamp;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JdbcOutboxStoreAdapter implements OutboxStorePort {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimDue(int limit, int leaseSeconds) {
        return jdbc.query(
                """
                WITH due AS (
                    SELECT id
                    FROM outbox_events
                    WHERE (status IN ('PENDING', 'FAILED')
                           AND next_attempt_at <= now())
                       OR (status = 'IN_FLIGHT'
                           AND lease_until <= now())
                    ORDER BY next_attempt_at, created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox_events AS event
                SET status = 'IN_FLIGHT',
                    attempts = event.attempts + 1,
                    lease_until = now() + (? * INTERVAL '1 second')
                FROM due
                WHERE event.id = due.id
                RETURNING event.id, event.order_id,
                          event.payload::text AS payload,
                          event.attempts, event.lease_until,
                          event.traceparent, event.tracestate
                """,
                (rs, row) -> new OutboxEvent(
                        rs.getObject("id", java.util.UUID.class),
                        rs.getObject("order_id", java.util.UUID.class),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getTimestamp("lease_until").toInstant(),
                        rs.getString("traceparent"),
                        rs.getString("tracestate")),
                limit,
                leaseSeconds);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(OutboxEvent event) {
        return jdbc.update("""
                UPDATE outbox_events
                SET status = 'PUBLISHED',
                    published_at = now(),
                    lease_until = NULL,
                    last_error = NULL
                WHERE id = ?
                  AND status = 'IN_FLIGHT'
                  AND lease_until = ?
                """, event.id(), Timestamp.from(event.leaseUntil())) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(OutboxEvent event, boolean dead, int retryDelaySeconds, String errorCode) {
        return jdbc.update(
                        """
                UPDATE outbox_events
                SET status = ?,
                    next_attempt_at = now() + (? * INTERVAL '1 second'),
                    lease_until = NULL,
                    last_error = ?
                WHERE id = ?
                  AND status = 'IN_FLIGHT'
                  AND lease_until = ?
                """,
                        dead ? "DEAD" : "FAILED",
                        retryDelaySeconds,
                        errorCode,
                        event.id(),
                        Timestamp.from(event.leaseUntil()))
                == 1;
    }
}
