package com.portafolio.notification_service.application.ports.out;

import com.portafolio.notification_service.domain.models.EmailDelivery;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryRepositoryPort {
    boolean insertIfAbsent(com.portafolio.notification_service.domain.models.EmailDelivery delivery);

    Optional<EmailDelivery> claimNext();

    void markSent(UUID sourceMessageId, int attempts);

    void markSendFailed(UUID sourceMessageId, int attempts);
}
