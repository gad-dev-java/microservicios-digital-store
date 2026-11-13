package com.portafolio.order_service.application.ports.out;

public interface OrderEventPublisherPort {
    void publish(OutboxEvent event);
}
