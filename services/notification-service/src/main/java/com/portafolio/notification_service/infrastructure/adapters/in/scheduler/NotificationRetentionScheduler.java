package com.portafolio.notification_service.infrastructure.adapters.in.scheduler;

import com.portafolio.notification_service.infrastructure.adapters.out.persistence.NotificationRetentionBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "retention.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRetentionScheduler {
    private static final int BATCH_SIZE = 1000;

    private final NotificationRetentionBatch retention;

    // Sin @Transactional: cada lote confirma de forma independiente.
    @Scheduled(cron = "${retention.purge.cron:0 15 3 * * *}", zone = "America/Lima")
    public void purgeDaily() {
        int total = 0;
        int deleted;
        do {
            deleted = retention.deleteSentEmails(BATCH_SIZE);
            total += deleted;
        } while (deleted > 0);

        log.info("Purga completada: tabla=email_deliveries, filas={}", total);
    }
}
