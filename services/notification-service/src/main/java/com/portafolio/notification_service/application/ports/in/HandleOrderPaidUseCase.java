package com.portafolio.notification_service.application.ports.in;

import com.portafolio.notification_service.domain.models.OrderPaidEvent;

public interface HandleOrderPaidUseCase {
    void handle(OrderPaidEvent event);
}
