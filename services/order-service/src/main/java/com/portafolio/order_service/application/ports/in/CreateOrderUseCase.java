package com.portafolio.order_service.application.ports.in;

public interface CreateOrderUseCase {
    Result create(Command command);
}
