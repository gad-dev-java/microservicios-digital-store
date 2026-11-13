package com.portafolio.notification_service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portafolio.notification_service.infrastructure.adapters.in.scheduler.NotificationRetentionScheduler;
import com.portafolio.notification_service.infrastructure.adapters.out.persistence.NotificationRetentionBatch;
import org.junit.jupiter.api.Test;

class NotificationRetentionSchedulerTest {
    @Test
    void drainsSentEmailsUntilTheBatchReturnsZero() {
        NotificationRetentionBatch retention = mock(NotificationRetentionBatch.class);

        when(retention.deleteSentEmails(1000)).thenReturn(1000, 3, 0);

        new NotificationRetentionScheduler(retention).purgeDaily();

        verify(retention, times(3)).deleteSentEmails(1000);
    }
}
