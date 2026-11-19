package com.portafolio.notification_service.infrastructure.config;

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
    private static final String PENDING_EMAILS =
            "SELECT count(*) FROM email_deliveries WHERE status IN ('PENDING', 'FAILED')";
    private static final String DEAD_EMAILS = "SELECT count(*) FROM email_deliveries WHERE status = 'DEAD'";

    @Test
    void gaugesReadCurrentEmailCountsOnEachScrape() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(PENDING_EMAILS, Long.class)).thenReturn(5L, 2L);
        when(jdbc.queryForObject(DEAD_EMAILS, Long.class)).thenReturn(1L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BusinessMetricsConfig().emailBusinessMetrics(jdbc).bindTo(registry);

        Gauge pending = registry.get("digital.store.emails.pending").gauge();
        assertEquals(5.0, pending.value());
        assertEquals(2.0, pending.value());
        assertEquals(1.0, registry.get("digital.store.emails.dead").gauge().value());

        verify(jdbc, times(2)).queryForObject(PENDING_EMAILS, Long.class);
    }

    @Test
    void databaseFailureOrMissingCountDoesNotBreakMetricsScrape() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(PENDING_EMAILS, Long.class))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        when(jdbc.queryForObject(DEAD_EMAILS, Long.class)).thenReturn(null);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BusinessMetricsConfig().emailBusinessMetrics(jdbc).bindTo(registry);

        assertTrue(Double.isNaN(
                registry.get("digital.store.emails.pending").gauge().value()));
        assertTrue(
                Double.isNaN(registry.get("digital.store.emails.dead").gauge().value()));
    }
}
