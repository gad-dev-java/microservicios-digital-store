package com.portafolio.notification_service.infrastructure.adapters.out.smtp;

import com.portafolio.notification_service.application.ports.out.MailDeliveryException;
import com.portafolio.notification_service.application.ports.out.MailSenderPort;
import com.portafolio.notification_service.domain.models.EmailDelivery;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSenderAdapter implements MailSenderPort {
    private final JavaMailSender mailSender;

    public SmtpMailSenderAdapter(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void send(EmailDelivery delivery) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("compras@digital-store.local");
        message.setTo(delivery.recipientEmail());
        message.setSubject(delivery.subject());
        message.setText(delivery.body());

        try {
            mailSender.send(message);
        } catch (MailException exception) {
            throw new MailDeliveryException(exception);
        }
    }
}
