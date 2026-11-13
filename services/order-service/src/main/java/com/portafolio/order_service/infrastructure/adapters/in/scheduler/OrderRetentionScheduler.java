package com.portafolio.order_service.infrastructure.adapters.in.scheduler;

import com.portafolio.order_service.infrastructure.adapters.out.IdempotencyPurgeBatch;
import com.portafolio.order_service.infrastructure.adapters.out.OrderRetentionBatch;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "retention.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderRetentionScheduler {
    private static final int BATCH_SIZE = 1000;

    private final IdempotencyPurgeBatch idempotency;
    private final OrderRetentionBatch retention;

    // Sin @Transactional: cada llamada al adaptador confirma su propio lote.
    @Scheduled(cron = "${retention.purge.cron:0 0 3 * * *}", zone = "America/Lima")
    public void purgeDaily() {
        purge("idempotency_requests", () -> idempotency.deleteExpired(BATCH_SIZE));
        purge("webhook_receipts", () -> retention.deleteProcessedWebhooks(BATCH_SIZE));
        purge("outbox_events", () -> retention.deletePublishedOutbox(BATCH_SIZE));
    }

    private void purge(String table, IntSupplier deleteBatch) {
        int total = 0;
        int deleted;
        do {
            deleted = deleteBatch.getAsInt();
            total += deleted;
        } while (deleted > 0);

        log.info("Purga completada: tabla={}, filas={}", table, total);
    }
}
