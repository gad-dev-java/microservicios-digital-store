package com.portafolio.notification_service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.portafolio.notification_service.application.ports.out.MailSenderPort;
import com.portafolio.notification_service.infrastructure.adapters.out.persistence.NotificationRetentionBatch;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=false", "app.notifications.sender.enabled=false"})
class NotificationRetentionIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NotificationRetentionBatch retention;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    MailSenderPort mailSender;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM email_deliveries");
    }

    @Test
    void deletesOnlyOldSentEmailsInIndependentBatches() {
        UUID oldA = seed("SENT", -90);
        UUID oldB = seed("SENT", -61);
        UUID recent = seed("SENT", -59);
        UUID withoutDate = seed("SENT", null);
        UUID pending = seed("PENDING", -90);
        UUID failed = seed("FAILED", -90);
        UUID inFlight = seed("IN_FLIGHT", -90);
        UUID dead = seed("DEAD", -90);

        assertEquals(1, retention.deleteSentEmails(1));

        // Revertir esta transacción no debe revertir el lote REQUIRES_NEW.
        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            assertEquals(1, retention.deleteSentEmails(1));
            outer.setRollbackOnly();
        });

        assertEquals(0, retention.deleteSentEmails(1));
        assertEquals(0, rows(oldA));
        assertEquals(0, rows(oldB));
        assertEquals(1, rows(recent));
        assertEquals(1, rows(withoutDate));
        assertEquals(1, rows(pending));
        assertEquals(1, rows(failed));
        assertEquals(1, rows(inFlight));
        assertEquals(1, rows(dead));
    }

    private UUID seed(String status, Integer sentInDays) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO email_deliveries
                    (source_message_id, order_id, notification_type,
                     recipient_email, subject, body,
                     status, created_at, sent_at)
                VALUES (?, ?, 'ORDER_PAID',
                        'buyer@example.test', 'Pago recibido', 'Contenido',
                        ?, ?, ?)
                """, id, UUID.randomUUID(), status, timestamp(-100), timestamp(sentInDays));
        return id;
    }

    private int rows(UUID id) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM email_deliveries
                WHERE source_message_id = ?
                """, Integer.class, id);
    }

    private static Timestamp timestamp(Integer daysFromNow) {
        return daysFromNow == null ? null : Timestamp.from(Instant.now().plus(daysFromNow, ChronoUnit.DAYS));
    }
}
