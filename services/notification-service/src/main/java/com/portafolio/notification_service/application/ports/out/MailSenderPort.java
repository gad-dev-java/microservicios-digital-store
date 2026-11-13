package com.portafolio.notification_service.application.ports.out;

import com.portafolio.notification_service.domain.models.EmailDelivery;

public interface MailSenderPort {
    void send(EmailDelivery delivery);
}
