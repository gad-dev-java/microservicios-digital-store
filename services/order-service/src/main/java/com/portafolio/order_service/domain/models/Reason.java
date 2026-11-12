package com.portafolio.order_service.domain.models;

public enum Reason {
    INVALID_ORDER_REQUEST,
    EMAIL_NOT_VERIFIED,
    PRODUCT_NOT_FOUND,
    PRODUCT_ALREADY_OWNED,
    IDEMPOTENCY_CONFLICT,
    IDEMPOTENCY_RACE,
    CATALOG_UNAVAILABLE,
    ORDER_NOT_FOUND
}
