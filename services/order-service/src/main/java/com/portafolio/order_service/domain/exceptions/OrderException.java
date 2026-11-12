package com.portafolio.order_service.domain.exceptions;

import com.portafolio.order_service.domain.models.Reason;

public class OrderException extends RuntimeException {
    private final Reason reason;

    public OrderException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
