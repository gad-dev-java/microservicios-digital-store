package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.OrderQueryPort;
import com.portafolio.order_service.domain.models.*;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcOrderQueryAdapter implements OrderQueryPort {
    private final JdbcTemplate jdbc;

    private static final String SELECT_SUMMARY = """
            SELECT o.id, o.buyer_email, o.status, o.total_amount,
                   o.currency, o.created_at, o.paid_at,
                   attempt.status AS payment_status,
                   attempt.review_reason
            FROM orders o
            LEFT JOIN LATERAL (
                SELECT pa.status, pa.review_reason
                FROM payment_attempts pa
                WHERE pa.order_id = o.id
                ORDER BY pa.created_at DESC, pa.id DESC
                LIMIT 1
            ) attempt ON TRUE
            """;

    private static final RowMapper<Summary> SUMMARY_MAPPER = (rs, rowNum) -> {
        Timestamp paidAt = rs.getTimestamp("paid_at");
        return new Summary(
                rs.getObject("id", UUID.class),
                rs.getString("buyer_email"),
                Status.valueOf(rs.getString("status")),
                rs.getBigDecimal("total_amount"),
                rs.getString("currency"),
                rs.getTimestamp("created_at").toInstant(),
                paidAt == null ? null : paidAt.toInstant(),
                rs.getString("payment_status"),
                rs.getString("review_reason"));
    };

    @Override
    public Page findMine(String buyerSub, int page, int size) {
        long total = Objects.requireNonNull(
                jdbc.queryForObject("SELECT count(*) FROM orders WHERE buyer_sub = ?", Long.class, buyerSub));

        List<Summary> items = jdbc.query(
                SELECT_SUMMARY
                        + " WHERE o.buyer_sub = ?"
                        + " ORDER BY o.created_at DESC, o.id DESC"
                        + " LIMIT ? OFFSET ?",
                SUMMARY_MAPPER,
                buyerSub,
                size,
                (long) page * size);

        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    @Override
    public Optional<Detail> findMineById(String buyerSub, UUID orderId) {
        List<Summary> summaries =
                jdbc.query(SELECT_SUMMARY + " WHERE o.id = ? AND o.buyer_sub = ?", SUMMARY_MAPPER, orderId, buyerSub);

        if (summaries.isEmpty()) {
            return Optional.empty();
        }

        List<OrderItem> items = jdbc.query(
                """
                        SELECT id, product_id, sku, title, unit_price
                        FROM order_items
                        WHERE order_id = ?
                        ORDER BY product_id
                        """,
                (rs, rowNum) -> new OrderItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("title"),
                        rs.getBigDecimal("unit_price")),
                orderId);

        return Optional.of(new Detail(summaries.getFirst(), items));
    }

    @Override
    public Page findAll(int page, int size) {
        long total = Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class));

        List<Summary> items = jdbc.query(
                SELECT_SUMMARY + " ORDER BY o.created_at DESC, o.id DESC" + " LIMIT ? OFFSET ?",
                SUMMARY_MAPPER,
                size,
                (long) page * size);

        return new Page(items, page, size, total, (total + size - 1) / size);
    }
}
