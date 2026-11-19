package com.portafolio.order_service.infrastructure.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class BusinessMetricsConfig {
    @Bean
    MeterBinder orderBusinessMetrics(JdbcTemplate jdbc) {
        return registry -> {
            Gauge.builder(
                            "digital.store.orders.paid",
                            jdbc,
                            source -> count(source, "SELECT count(*) FROM orders WHERE status = 'PAID'"))
                    .register(registry);

            Gauge.builder(
                            "digital.store.payments.review.required",
                            jdbc,
                            source -> count(
                                    source, "SELECT count(*) FROM payment_attempts WHERE status = 'REVIEW_REQUIRED'"))
                    .register(registry);

            Gauge.builder(
                            "digital.store.outbox.pending",
                            jdbc,
                            source -> count(
                                    source, "SELECT count(*) FROM outbox_events WHERE status IN ('PENDING', 'FAILED')"))
                    .register(registry);
        };
    }

    private static double count(JdbcTemplate jdbc, String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? Double.NaN : value.doubleValue();
        } catch (DataAccessException exception) {
            return Double.NaN;
        }
    }
}
