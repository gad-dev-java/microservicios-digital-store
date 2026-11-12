package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.Attempt;
import com.portafolio.order_service.application.ports.out.PaymentConfirmationPort;
import com.portafolio.order_service.application.ports.out.ProviderOrderSnapshot;
import com.portafolio.order_service.application.ports.out.StoreOrder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcPaymentConfirmationAdapter implements PaymentConfirmationPort {
    private final JdbcTemplate jdbc;

    @Override
    public Optional<Attempt> lockAttempt(String providerOrderId, String externalReference) {
        List<Attempt> rows = jdbc.query(
                """
                SELECT id, order_id, provider_order_id, amount,
                       currency, status, review_reason
                FROM payment_attempts
                WHERE provider_order_id = ? OR id::text = ?
                ORDER BY CASE WHEN provider_order_id = ? THEN 0 ELSE 1 END
                LIMIT 1
                FOR UPDATE
                """,
                (rs, rowNum) -> new Attempt(
                        rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("provider_order_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("review_reason")),
                providerOrderId,
                externalReference,
                providerOrderId);
        return rows.stream().findFirst();
    }

    @Override
    public StoreOrder lockOrder(UUID orderId) {
        return jdbc.queryForObject(
                """
                SELECT o.id, o.buyer_sub, o.buyer_email, o.total_amount,
                       o.currency, o.status,
                       (SELECT count(*)
                        FROM order_items item
                        WHERE item.order_id = o.id) AS item_count
                FROM orders o
                WHERE o.id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new StoreOrder(
                        rs.getObject("id", UUID.class),
                        rs.getString("buyer_sub"),
                        rs.getString("buyer_email"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getInt("item_count")),
                orderId);
    }

    @Override
    public void markReview(Attempt attempt, ProviderOrderSnapshot provider, String reason) {
        jdbc.update("""
                UPDATE payment_attempts
                SET status = 'REVIEW_REQUIRED',
                    review_reason = ?,
                    provider_order_id = COALESCE(provider_order_id, ?),
                    provider_status = ?,
                    provider_status_detail = ?
                WHERE id = ?
                """, reason, provider.id(), provider.status(), provider.statusDetail(), attempt.id());
    }

    @Override
    public void updateProviderStatus(Attempt attempt, ProviderOrderSnapshot provider) {
        jdbc.update("""
                UPDATE payment_attempts
                SET provider_order_id = COALESCE(provider_order_id, ?),
                    provider_status = ?,
                    provider_status_detail = ?
                WHERE id = ?
                """, provider.id(), provider.status(), provider.statusDetail(), attempt.id());
    }

    @Override
    public void approveAttempt(Attempt attempt, ProviderOrderSnapshot provider, String reviewReason) {
        jdbc.update("""
                UPDATE payment_attempts
                SET status = 'APPROVED',
                    provider_order_id = COALESCE(provider_order_id, ?),
                    provider_status = ?,
                    provider_status_detail = ?,
                    review_reason = ?
                WHERE id = ?
                """, provider.id(), provider.status(), provider.statusDetail(), reviewReason, attempt.id());
    }

    @Override
    public void markOrderPaid(UUID orderId) {
        int updated = jdbc.update("""
                UPDATE orders
                SET status = 'PAID', paid_at = now()
                WHERE id = ? AND status <> 'PAID'
                """, orderId);
        if (updated != 1) {
            throw new IllegalStateException("El pedido cambió durante la confirmación");
        }
    }

    @Override
    public int grantEntitlements(StoreOrder order) {
        return jdbc.update("""
                INSERT INTO entitlements (id, buyer_sub, product_id, order_id)
                SELECT gen_random_uuid(), ?, item.product_id, ?
                FROM order_items item
                WHERE item.order_id = ?
                ON CONFLICT DO NOTHING
                """, order.buyerSub(), order.id(), order.id());
    }

    @Override
    public void enqueueOrderPaid(StoreOrder order) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, order_id, event_type, payload)
                VALUES (
                    ?, ?, 'ORDER_PAID',
                    jsonb_build_object(
                        'eventId', ?::text,
                        'eventType', 'ORDER_PAID',
                        'version', 1,
                        'orderId', ?::text,
                        'buyerSub', ?,
                        'buyerEmail', ?,
                        'productIds', (
                            SELECT COALESCE(
                                jsonb_agg(product_id::text ORDER BY product_id),
                                '[]'::jsonb)
                            FROM order_items
                            WHERE order_id = ?
                        )
                    )
                )
                ON CONFLICT (order_id, event_type) DO NOTHING
                """, eventId, order.id(), eventId, order.id(), order.buyerSub(), order.buyerEmail(), order.id());
    }
}
