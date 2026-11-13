package com.portafolio.order_service.application.ports.out;

public interface TraceContextPort {
    TraceHeaders currentHeaders();

    void runWithParent(String traceparent, String tracestate, String spanName, Runnable action);
}
