package com.portafolio.notification_service.application.service;

import com.portafolio.notification_service.application.ports.in.HandleOrderPaidUseCase;
import com.portafolio.notification_service.application.ports.out.DeliveryRepositoryPort;
import com.portafolio.notification_service.application.ports.out.MailDeliveryException;
import com.portafolio.notification_service.application.ports.out.MailSenderPort;
import com.portafolio.notification_service.domain.models.EmailDelivery;
import com.portafolio.notification_service.domain.models.OrderPaidEvent;
import org.springframework.stereotype.Service;

@Service
public class OrderPaidEmailService implements HandleOrderPaidUseCase {
    private final DeliveryRepositoryPort deliveries;
    private final MailSenderPort mailSender;

    public OrderPaidEmailService(DeliveryRepositoryPort deliveries, MailSenderPort mailSender) {
        this.deliveries = deliveries;
        this.mailSender = mailSender;
    }

    @Override
    public void handle(OrderPaidEvent event) {
        String products = String.join("\n- ", event.titles());
        String body = """
                Tu compra de prueba fue confirmada.

                Pedido: %s
                Total: %s %s
                Productos:
                - %s

                Puedes consultar tus pedidos en la tienda.
                """.formatted(event.orderId(), event.amount().toPlainString(), event.currency(), products);

        deliveries.insertIfAbsent(new EmailDelivery(
                event.eventId(),
                event.orderId(),
                event.buyerEmail(),
                "Compra confirmada",
                body,
                event.traceparent(),
                event.tracestate(),
                0));
    }

    public void sendNext() {
        deliveries.claimNext().ifPresent(delivery -> {
            try {
                mailSender.send(delivery);
            } catch (MailDeliveryException exception) {
                deliveries.markSendFailed(delivery.sourceMessageId(), delivery.attempts());
                return;
            }
            deliveries.markSent(delivery.sourceMessageId(), delivery.attempts());
        });
    }
}
