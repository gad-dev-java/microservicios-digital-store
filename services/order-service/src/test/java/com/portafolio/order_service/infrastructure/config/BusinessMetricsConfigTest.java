package com.portafolio.order_service.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class BusinessMetricsConfigTest {
    private static final String PAID_ORDERS = "SELECT count(*) FROM orders WHERE status = 'PAID'";
    private static final String REVIEW_REQUIRED =
            "SELECT count(*) FROM payment_attempts WHERE status = 'REVIEW_REQUIRED'";
    private static final String PENDING_OUTBOX =
            "SELECT count(*) FROM outbox_events WHERE status IN ('PENDING', 'FAILED')";

    @Test
    void gaugesReadCurrentBusinessCountsOnEachScrape() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(PAID_ORDERS, Long.class)).thenReturn(2L, 3L);
        when(jdbc.queryForObject(REVIEW_REQUIRED, Long.class)).thenReturn(1L);
        when(jdbc.queryForObject(PENDING_OUTBOX, Long.class)).thenReturn(4L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BusinessMetricsConfig().orderBusinessMetrics(jdbc).bindTo(registry);

        Gauge paid = registry.get("digital.store.orders.paid").gauge();
        assertEquals(2.0, paid.value());
        assertEquals(3.0, paid.value());
        assertEquals(
                1.0,
                registry.get("digital.store.payments.review.required").gauge().value());
        assertEquals(4.0, registry.get("digital.store.outbox.pending").gauge().value());

        verify(jdbc, times(2)).queryForObject(PAID_ORDERS, Long.class);
    }

    @Test
    void databaseFailureOrMissingCountDoesNotBreakMetricsScrape() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(PAID_ORDERS, Long.class))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        when(jdbc.queryForObject(REVIEW_REQUIRED, Long.class)).thenReturn(null);
        when(jdbc.queryForObject(PENDING_OUTBOX, Long.class)).thenReturn(0L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BusinessMetricsConfig().orderBusinessMetrics(jdbc).bindTo(registry);

        assertTrue(
                Double.isNaN(registry.get("digital.store.orders.paid").gauge().value()));
        assertTrue(Double.isNaN(
                registry.get("digital.store.payments.review.required").gauge().value()));
        assertEquals(0.0, registry.get("digital.store.outbox.pending").gauge().value());
    }
}
