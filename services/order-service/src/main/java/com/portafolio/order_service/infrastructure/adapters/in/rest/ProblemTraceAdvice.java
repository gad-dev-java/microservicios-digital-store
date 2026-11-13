package com.portafolio.order_service.infrastructure.adapters.in.rest;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@ControllerAdvice
public class ProblemTraceAdvice implements ResponseBodyAdvice<Object> {
    private final ObjectProvider<Tracer> tracers;

    public ProblemTraceAdvice(ObjectProvider<Tracer> tracers) {
        this.tracers = tracers;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        Tracer tracer = tracers.getIfAvailable();
        if (body instanceof ProblemDetail problem && tracer != null && tracer.currentSpan() != null) {
            problem.setProperty("traceId", tracer.currentSpan().context().traceId());
        }
        return body;
    }
}
