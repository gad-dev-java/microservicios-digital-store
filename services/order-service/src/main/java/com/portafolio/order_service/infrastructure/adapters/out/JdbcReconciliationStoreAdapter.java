package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.ReconcileCandidate;
import com.portafolio.order_service.application.ports.out.ReconciliationStorePort;
import com.portafolio.order_service.domain.models.Attempt;
import com.portafolio.order_service.domain.models.OrderItem;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JdbcReconciliationStoreAdapter implements ReconciliationStorePort {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public List<ReconcileCandidate> claimDue(int limit) {
        List<ReconcileCandidate> candidates = jdbc.query(
                """
                SELECT id, status, provider_order_id,
                       created_at, reconcile_attempts
                FROM payment_attempts
                WHERE status IN ('CREATING', 'PENDING')
                  AND next_reconcile_at <= now()
                ORDER BY next_reconcile_at, created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, row) -> new ReconcileCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("provider_order_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("reconcile_attempts") + 1),
                limit);

        for (ReconcileCandidate candidate : candidates) {
            jdbc.update("""
                    UPDATE payment_attempts
                    SET next_reconcile_at = now() + INTERVAL '5 minutes',
                        reconcile_attempts = reconcile_attempts + 1
                    WHERE id = ?
                    """, candidate.id());
        }

        // La transacción termina antes de llamar a Mercado Pago.
        return candidates;
    }

    @Override
    public Attempt loadAttempt(UUID attemptId) {
        Attempt stored = jdbc.queryForObject(
                """
                SELECT p.id, p.order_id, p.provider_idempotency_key,
                       p.amount, p.currency, o.buyer_email
                FROM payment_attempts p
                JOIN orders o ON o.id = p.order_id
                WHERE p.id = ? AND p.status = 'CREATING'
                """,
                (rs, row) -> new Attempt(
                        rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("provider_idempotency_key", UUID.class),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("buyer_email"),
                        List.of()),
                attemptId);

        List<OrderItem> items = jdbc.query(
                """
                SELECT id, product_id, sku, title, unit_price
                FROM order_items
                WHERE order_id = ?
                ORDER BY product_id
                """,
                (rs, row) -> new OrderItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("title"),
                        rs.getBigDecimal("unit_price")),
                stored.orderId());

        return new Attempt(
                stored.id(),
                stored.orderId(),
                stored.providerIdempotencyKey(),
                stored.amount(),
                stored.currency(),
                stored.buyerEmail(),
                items);
    }

    @Override
    public void postpone(UUID attemptId, Duration delay) {
        long seconds = Math.max(1, delay.toSeconds());
        jdbc.update("""
                UPDATE payment_attempts
                SET next_reconcile_at =
                    now() + (? * INTERVAL '1 second')
                WHERE id = ?
                  AND status IN ('CREATING', 'PENDING')
                """, seconds, attemptId);
    }

    @Override
    public void requireReview(UUID attemptId, String reason) {
        jdbc.update("""
                UPDATE payment_attempts
                SET status = 'REVIEW_REQUIRED',
                    review_reason = ?
                WHERE id = ?
                  AND status IN ('CREATING', 'PENDING')
                """, reason, attemptId);
    }
}
