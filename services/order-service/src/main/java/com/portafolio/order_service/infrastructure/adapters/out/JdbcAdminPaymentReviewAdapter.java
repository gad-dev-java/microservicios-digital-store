package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.AdminPaymentReviewPort;
import com.portafolio.order_service.application.ports.out.ReviewAttempt;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcAdminPaymentReviewAdapter implements AdminPaymentReviewPort {
    private final JdbcTemplate jdbc;

    private static final String SELECT = """
            SELECT id, order_id, provider_order_id, status,
                   provider_status, provider_status_detail,
                   review_reason, resolution_reason, resolved_by,
                   created_at, updated_at, resolved_at
            FROM payment_attempts
            """;

    private static final RowMapper<ReviewAttempt> MAPPER = (rs, row) -> {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new ReviewAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("provider_order_id"),
                rs.getString("status"),
                rs.getString("provider_status"),
                rs.getString("provider_status_detail"),
                rs.getString("review_reason"),
                rs.getString("resolution_reason"),
                rs.getString("resolved_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant());
    };

    @Override
    public Optional<ReviewAttempt> find(UUID attemptId) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, attemptId).stream().findFirst();
    }

    @Override
    public Optional<ReviewAttempt> lock(UUID attemptId) {
        return jdbc.query(SELECT + " WHERE id = ? FOR UPDATE", MAPPER, attemptId).stream()
                .findFirst();
    }

    @Override
    public List<ReviewAttempt> history(UUID orderId) {
        return jdbc.query(SELECT + """
                         WHERE order_id = ?
                         ORDER BY created_at ASC, id ASC
                        """, MAPPER, orderId);
    }

    @Override
    public void changeReviewReason(UUID attemptId, String reason) {
        jdbc.update("""
                UPDATE payment_attempts
                SET review_reason = ?
                WHERE id = ? AND status = 'REVIEW_REQUIRED'
                """, reason, attemptId);
    }

    @Override
    public int resolve(UUID attemptId, String adminSub, String reason) {
        return jdbc.update("""
                UPDATE payment_attempts
                SET status = 'ERROR',
                    resolution_reason = ?,
                    resolved_by = ?,
                    resolved_at = now()
                WHERE id = ?
                  AND status = 'REVIEW_REQUIRED'
                  AND review_reason = 'UNRESOLVED_TIMEOUT'
                  AND provider_order_id IS NULL
                """, reason, adminSub, attemptId);
    }
}
