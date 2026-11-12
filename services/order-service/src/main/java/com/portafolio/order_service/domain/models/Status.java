package com.portafolio.order_service.domain.models;

public enum Status {
    CREATED,
    PENDING_PAYMENT,
    PAID,
    PAYMENT_FAILED;

    public boolean allowsCheckout() {
        return this == CREATED || this == PENDING_PAYMENT || this == PAYMENT_FAILED;
    }
}
