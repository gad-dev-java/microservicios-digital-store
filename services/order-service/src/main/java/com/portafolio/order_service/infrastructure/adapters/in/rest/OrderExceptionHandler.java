package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OrderExceptionHandler {
    @ExceptionHandler(OrderException.class)
    ResponseEntity<ProblemDetail> handle(OrderException exception) {
        HttpStatus status = switch (exception.reason()) {
            case INVALID_ORDER_REQUEST, INVALID_REVIEW_RESOLUTION -> HttpStatus.BAD_REQUEST;
            case EMAIL_NOT_VERIFIED -> HttpStatus.FORBIDDEN;
            case PRODUCT_NOT_FOUND, ORDER_NOT_FOUND, PAYMENT_ATTEMPT_NOT_FOUND, LIBRARY_ITEM_NOT_FOUND ->
                HttpStatus.NOT_FOUND;
            case PRODUCT_ALREADY_OWNED,
                    IDEMPOTENCY_CONFLICT,
                    ORDER_NOT_PAYABLE,
                    PAYMENT_IN_PROGRESS,
                    PAYMENT_REVIEW_CONFLICT -> HttpStatus.CONFLICT;
            case CATALOG_UNAVAILABLE, PAYMENT_PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case IDEMPOTENCY_RACE -> HttpStatus.INTERNAL_SERVER_ERROR;
            case DOWNLOAD_BUSY -> HttpStatus.TOO_MANY_REQUESTS;
        };

        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        detail.setProperty("code", exception.reason().name());

        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (exception.reason() == Reason.CATALOG_UNAVAILABLE
                || exception.reason() == Reason.PAYMENT_PROVIDER_UNAVAILABLE) {
            response.header("Retry-After", "5");
        }
        return response.body(detail);
    }
}
