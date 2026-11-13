package com.portafolio.order_service.application.ports.out;

import com.portafolio.order_service.application.ports.in.LibraryUseCase.Page;
import java.util.UUID;

public interface LibraryRepositoryPort {
    Page findMine(String buyerSub, int page, int size);

    boolean hasEntitlement(String buyerSub, UUID productId);
}
