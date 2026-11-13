package com.portafolio.order_service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portafolio.order_service.infrastructure.adapters.in.scheduler.OrderRetentionScheduler;
import com.portafolio.order_service.infrastructure.adapters.out.IdempotencyPurgeBatch;
import com.portafolio.order_service.infrastructure.adapters.out.OrderRetentionBatch;
import org.junit.jupiter.api.Test;

class OrderRetentionSchedulerTest {
    @Test
    void drainsEachTableUntilTheBatchReturnsZero() {
        IdempotencyPurgeBatch idempotency = mock(IdempotencyPurgeBatch.class);
        OrderRetentionBatch retention = mock(OrderRetentionBatch.class);

        when(idempotency.deleteExpired(1000)).thenReturn(1000, 2, 0);
        when(retention.deleteProcessedWebhooks(1000)).thenReturn(1, 0);
        when(retention.deletePublishedOutbox(1000)).thenReturn(0);

        new OrderRetentionScheduler(idempotency, retention).purgeDaily();

        verify(idempotency, times(3)).deleteExpired(1000);
        verify(retention, times(2)).deleteProcessedWebhooks(1000);
        verify(retention).deletePublishedOutbox(1000);
    }
}
