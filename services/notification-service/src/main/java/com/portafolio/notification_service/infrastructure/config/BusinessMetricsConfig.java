package com.portafolio.notification_service.infrastructure.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class BusinessMetricsConfig {
    @Bean
    MeterBinder emailBusinessMetrics(JdbcTemplate jdbc) {
        return registry -> {
            Gauge.builder(
                            "digital.store.emails.pending",
                            jdbc,
                            source -> count(
                                    source,
                                    "SELECT count(*) FROM email_deliveries WHERE status IN ('PENDING', 'FAILED')"))
                    .register(registry);

            Gauge.builder(
                            "digital.store.emails.dead",
                            jdbc,
                            source -> count(source, "SELECT count(*) FROM email_deliveries WHERE status = 'DEAD'"))
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
