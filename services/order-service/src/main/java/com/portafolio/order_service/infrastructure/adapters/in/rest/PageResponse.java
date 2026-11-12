package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.domain.models.Page;
import java.util.List;

public record PageResponse(List<SummaryResponse> items, int page, int size, long totalItems, long totalPages) {
    static PageResponse from(Page source) {
        return new PageResponse(
                source.items().stream().map(SummaryResponse::from).toList(),
                source.page(),
                source.size(),
                source.totalItems(),
                source.totalPages());
    }
}
