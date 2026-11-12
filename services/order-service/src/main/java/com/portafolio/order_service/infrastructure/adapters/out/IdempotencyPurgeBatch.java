package com.portafolio.order_service.infrastructure.adapters.out;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class IdempotencyPurgeBatch {
    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpired(int batchSize) {
        Integer deleted =
                jdbcTemplate.queryForObject("SELECT purge_expired_idempotency_requests(?)", Integer.class, batchSize);
        return Objects.requireNonNull(deleted);
    }
}
