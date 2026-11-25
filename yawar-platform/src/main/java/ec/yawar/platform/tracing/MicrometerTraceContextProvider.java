package ec.yawar.platform.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Uses Micrometer Tracing (OpenTelemetry bridge in the services): the producer side reads the current span,
 * the consumer side starts a CONSUMER span whose parent is the received {@code traceparent}.
 */
public class MicrometerTraceContextProvider extends MdcTraceContextProvider {

    private final Tracer tracer;
    private final Propagator propagator;

    public MicrometerTraceContextProvider(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    protected String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return super.currentTraceparent();
        }
        return traceparent(span.context());
    }

    static String traceparent(TraceContext context) {
        boolean sampled = Boolean.TRUE.equals(context.sampled());
        return "00-" + context.traceId() + "-" + context.spanId() + (sampled ? "-01" : "-00");
    }

    @Override
    public <T> Callable<T> propagate(Callable<T> task) {
        Span span = tracer.currentSpan();
        Callable<T> withMdc = super.propagate(task);
        if (span == null) {
            return withMdc;
        }
        return () -> {
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                return withMdc.call();
            }
        };
    }

    @Override
    public Scope open(ec.yawar.platform.tracing.TraceContext received, String spanName) {
        Scope mdcScope = super.open(received, spanName);
        Span.Builder builder = received.traceparent() == null
                ? tracer.spanBuilder()
                : propagator.extract(Map.of(Correlation.TRACEPARENT, received.traceparent()), Map::get);
        Span span = builder.name(spanName).kind(Span.Kind.CONSUMER).start();
        Tracer.SpanInScope inScope = tracer.withSpan(span);
        return () -> {
            inScope.close();
            span.end();
            mdcScope.close();
        };
    }
}
