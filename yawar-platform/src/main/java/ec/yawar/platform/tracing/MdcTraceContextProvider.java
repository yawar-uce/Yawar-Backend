package ec.yawar.platform.tracing;

import org.slf4j.MDC;

/** Trace context kept in the logging MDC only; used when no Micrometer tracer is configured. */
public class MdcTraceContextProvider implements TraceContextProvider {

    @Override
    public TraceContext current() {
        String correlationId = Correlation.sanitize(MDC.get(Correlation.MDC_CORRELATION_ID));
        return new TraceContext(
                currentTraceparent(),
                correlationId != null ? correlationId : Correlation.newId(),
                null);
    }

    /** Traceparent of the current thread, or {@code null}. Subclasses read it from the tracer. */
    protected String currentTraceparent() {
        return MDC.get(Correlation.MDC_TRACEPARENT);
    }

    @Override
    public Scope open(TraceContext received, String spanName) {
        String previousCorrelation = MDC.get(Correlation.MDC_CORRELATION_ID);
        String previousTraceparent = MDC.get(Correlation.MDC_TRACEPARENT);
        String correlationId = Correlation.sanitize(received.correlationId());
        put(Correlation.MDC_CORRELATION_ID, correlationId != null ? correlationId : Correlation.newId());
        put(Correlation.MDC_TRACEPARENT, received.traceparent());
        return () -> {
            put(Correlation.MDC_CORRELATION_ID, previousCorrelation);
            put(Correlation.MDC_TRACEPARENT, previousTraceparent);
        };
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
