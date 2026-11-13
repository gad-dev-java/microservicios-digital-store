package com.portafolio.notification_service.application.ports.out;

public class MailDeliveryException extends RuntimeException {
    public MailDeliveryException(Throwable cause) {
        super(cause);
    }
}
