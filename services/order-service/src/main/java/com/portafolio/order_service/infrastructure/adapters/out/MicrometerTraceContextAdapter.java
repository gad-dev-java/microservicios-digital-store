package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.TraceContextPort;
import com.portafolio.order_service.application.ports.out.TraceHeaders;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class MicrometerTraceContextAdapter implements TraceContextPort {
    private final ObjectProvider<Tracer> tracers;
    private final ObjectProvider<Propagator> propagators;

    public MicrometerTraceContextAdapter(ObjectProvider<Tracer> tracers, ObjectProvider<Propagator> propagators) {
        this.tracers = tracers;
        this.propagators = propagators;
    }

    @Override
    public TraceHeaders currentHeaders() {
        Tracer tracer = tracers.getIfAvailable();
        Propagator propagator = propagators.getIfAvailable();

        if (tracer == null || propagator == null || tracer.currentSpan() == null) {
            return new TraceHeaders(null, null);
        }

        Map<String, String> headers = new HashMap<>();
        propagator.inject(tracer.currentSpan().context(), headers, (carrier, key, value) -> carrier.put(key, value));

        return new TraceHeaders(headers.get("traceparent"), headers.get("tracestate"));
    }

    @Override
    public void runWithParent(String traceparent, String tracestate, String spanName, Runnable action) {
        Tracer tracer = tracers.getIfAvailable();
        Propagator propagator = propagators.getIfAvailable();

        if (tracer == null || propagator == null) {
            action.run();
            return;
        }

        Span.Builder builder;
        if (traceparent != null && !traceparent.isBlank()) {
            Map<String, String> headers = new HashMap<>();
            headers.put("traceparent", traceparent);
            if (tracestate != null && !tracestate.isBlank()) {
                headers.put("tracestate", tracestate);
            }
            builder = propagator.extract(headers, Map::get);
        } else {
            builder = tracer.spanBuilder().setNoParent();
        }

        Span span = builder.name(spanName).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            action.run();
        } catch (RuntimeException exception) {
            span.error(exception);
            throw exception;
        } finally {
            span.end();
        }
    }
}
