package com.portafolio.notification_service.application.service;

import com.portafolio.notification_service.application.ports.in.HandleOrderPaidUseCase;
import com.portafolio.notification_service.application.ports.out.DeliveryRepositoryPort;
import com.portafolio.notification_service.application.ports.out.MailDeliveryException;
import com.portafolio.notification_service.application.ports.out.MailSenderPort;
import com.portafolio.notification_service.application.ports.out.TraceContextPort;
import com.portafolio.notification_service.domain.models.EmailDelivery;
import com.portafolio.notification_service.domain.models.OrderPaidEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderPaidEmailService implements HandleOrderPaidUseCase {
    private final DeliveryRepositoryPort deliveries;
    private final MailSenderPort mailSender;
    private final TraceContextPort traceContext;

    public OrderPaidEmailService(
            DeliveryRepositoryPort deliveries, MailSenderPort mailSender, TraceContextPort traceContext) {
        this.deliveries = deliveries;
        this.mailSender = mailSender;
        this.traceContext = traceContext;
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
        deliveries
                .claimNext()
                .ifPresent(delivery ->
                        traceContext.runWithParent(delivery.traceparent(), delivery.tracestate(), "email.send", () -> {
                            try {
                                mailSender.send(delivery);
                                deliveries.markSent(delivery.sourceMessageId(), delivery.attempts());
                                log.info(
                                        "Correo enviado: deliveryId={}, orderId={}",
                                        delivery.sourceMessageId(),
                                        delivery.orderId());
                            } catch (MailDeliveryException exception) {
                                deliveries.markSendFailed(delivery.sourceMessageId(), delivery.attempts());
                                log.warn(
                                        "Correo pendiente de reintento: deliveryId={}, orderId={}",
                                        delivery.sourceMessageId(),
                                        delivery.orderId());
                            }
                        }));
    }
}
