package com.portafolio.order_service.application.ports.out;

public record TraceHeaders(String traceparent, String tracestate) {}
