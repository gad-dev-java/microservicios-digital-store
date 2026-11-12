package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.domain.models.Detail;
import java.util.List;

public record DetailResponse(SummaryResponse order, List<ItemResponse> items) {
    static DetailResponse from(Detail source) {
        return new DetailResponse(
                SummaryResponse.from(source.order()),
                source.items().stream()
                        .map(item -> new ItemResponse(item.productId(), item.sku(), item.title(), item.unitPrice()))
                        .toList());
    }
}
