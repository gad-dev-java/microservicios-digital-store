package com.portafolio.order_service.infrastructure.adapters.in.scheduler;

import com.portafolio.order_service.infrastructure.adapters.out.IdempotencyPurgeBatch;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IdempotencyPurgeScheduler {
    private static final int BATCH_SIZE = 1000;
    private final IdempotencyPurgeBatch purgeBatch;

    @Scheduled(cron = "0 0 3 * * *", zone = "America/Lima")
    public void purgeDaily() {
        int deleted;
        do {
            deleted = purgeBatch.deleteExpired(BATCH_SIZE);
        } while (deleted > 0);
    }
}
