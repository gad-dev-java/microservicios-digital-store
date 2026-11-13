package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.in.LibraryUseCase;
import com.portafolio.order_service.application.ports.out.LibraryRepositoryPort;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLibraryRepositoryAdapter implements LibraryRepositoryPort {
    private final JdbcTemplate jdbc;

    public JdbcLibraryRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public LibraryUseCase.Page findMine(String buyerSub, int page, int size) {
        long total = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*)
                FROM entitlements e
                JOIN orders o ON o.id = e.order_id AND o.status = 'PAID'
                WHERE e.buyer_sub = ?
                """, Long.class, buyerSub));

        List<LibraryUseCase.Item> items = jdbc.query(
                """
                SELECT e.product_id, e.order_id, e.granted_at,
                       item.sku, item.title
                FROM entitlements e
                JOIN orders o ON o.id = e.order_id AND o.status = 'PAID'
                JOIN order_items item
                  ON item.order_id = e.order_id
                 AND item.product_id = e.product_id
                WHERE e.buyer_sub = ?
                ORDER BY e.granted_at DESC, e.product_id
                LIMIT ? OFFSET ?
                """,
                (rs, row) -> new LibraryUseCase.Item(
                        rs.getObject("product_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("title"),
                        rs.getTimestamp("granted_at").toInstant()),
                buyerSub,
                size,
                (long) page * size);

        return new LibraryUseCase.Page(items, page, size, total, (total + size - 1) / size);
    }

    @Override
    public boolean hasEntitlement(String buyerSub, UUID productId) {
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM entitlements e
                    JOIN orders o ON o.id = e.order_id AND o.status = 'PAID'
                    WHERE e.buyer_sub = ? AND e.product_id = ?
                )
                """, Boolean.class, buyerSub, productId);
        return Boolean.TRUE.equals(found);
    }
}
