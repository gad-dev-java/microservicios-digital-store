package com.portafolio.order_service.application.ports.out;

import java.util.List;

public interface OutboxStorePort {
    List<OutboxEvent> claimDue(int limit, int leaseSeconds);

    boolean markPublished(OutboxEvent event);

    boolean markFailed(OutboxEvent event, boolean dead, int retryDelaySeconds, String errorCode);
}
