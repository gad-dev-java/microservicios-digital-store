package com.portafolio.notification_service.infrastructure.adapters.in.kafka;

public class InvalidOrderPaidEventException extends RuntimeException {
    public InvalidOrderPaidEventException(Throwable cause) {
        super("Evento ORDER_PAID inválido", cause);
    }

    public InvalidOrderPaidEventException() {
        super("Evento ORDER_PAID inválido");
    }
}
