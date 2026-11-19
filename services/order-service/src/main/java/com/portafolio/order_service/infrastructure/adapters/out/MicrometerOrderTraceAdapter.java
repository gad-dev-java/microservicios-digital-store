package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.OrderTracePort;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MicrometerOrderTraceAdapter implements OrderTracePort {
    private final Tracer tracer;

    @Override
    public void tagOrder(UUID orderId) {
        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag("order.id", orderId.toString());
        }
    }
}
