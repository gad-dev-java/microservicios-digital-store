package com.portafolio.notification_service.application.ports.out;

public interface TraceContextPort {
    void runWithParent(String traceparent, String tracestate, String spanName, Runnable action);
}
