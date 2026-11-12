package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.OrderStorePort;
import com.portafolio.order_service.application.ports.out.StoredOrder;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Order;
import com.portafolio.order_service.domain.models.OrderItem;
import com.portafolio.order_service.domain.models.Reason;
import com.portafolio.order_service.domain.models.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class JdbcOrderStoreAdapter implements OrderStorePort {
    private static final String OPERATION = "CREATE_ORDER";
    private final JdbcTemplate jdbc;

    @Override
    public Optional<StoredOrder> findByIdempotency(String actorSub, String key) {
        List<OrderRow> rows = jdbc.query(
                """
                SELECT r.request_hash, o.id, o.buyer_sub, o.buyer_email,
                       o.total_amount, o.currency, o.status
                FROM idempotency_requests r
                JOIN orders o ON o.id = r.order_id
                WHERE r.actor_sub = ? AND r.operation = ? AND r.idempotency_key = ?
                """,
                (rs, number) -> new OrderRow(
                        rs.getString("request_hash"),
                        rs.getObject("id", UUID.class),
                        rs.getString("buyer_sub"),
                        rs.getString("buyer_email"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("currency"),
                        Status.valueOf(rs.getString("status"))),
                actorSub,
                OPERATION,
                key);

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        OrderRow row = rows.getFirst();
        List<OrderItem> items = jdbc.query(
                """
                SELECT id, product_id, sku, title, unit_price
                FROM order_items
                WHERE order_id = ?
                ORDER BY product_id
                """,
                (rs, number) -> new OrderItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("title"),
                        rs.getBigDecimal("unit_price")),
                row.id());

        Order order =
                new Order(row.id(), row.buyerSub(), row.buyerEmail(), items, row.total(), row.currency(), row.status());

        return Optional.of(new StoredOrder(order, row.hash()));
    }

    @Override
    public boolean ownsProduct(String buyerSub, UUID productId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM entitlements
                    WHERE buyer_sub = ? AND product_id = ?
                )
                """, Boolean.class, buyerSub, productId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void insert(Order order, String key, String requestHash) {
        jdbc.update(
                """
                INSERT INTO orders
                    (id, buyer_sub, buyer_email, total_amount, currency, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                order.id(),
                order.buyerSub(),
                order.buyerEmail(),
                order.totalAmount(),
                order.currency(),
                order.status().name());

        for (OrderItem item : order.items()) {
            jdbc.update(
                    """
                    INSERT INTO order_items
                        (id, order_id, product_id, sku, title,
                         unit_price, quantity, line_total)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                    """,
                    item.id(),
                    order.id(),
                    item.productId(),
                    item.sku(),
                    item.title(),
                    item.unitPrice(),
                    item.unitPrice());
        }

        int inserted = jdbc.update("""
                INSERT INTO idempotency_requests
                    (id, actor_sub, operation, idempotency_key,
                     request_hash, order_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (actor_sub, operation, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), order.buyerSub(), OPERATION, key, requestHash, order.id());

        if (inserted == 0) {
            throw new OrderException(Reason.IDEMPOTENCY_RACE, "Otra solicitud utilizó la misma clave");
        }
    }
}
