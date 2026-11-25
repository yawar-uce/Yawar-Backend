package ec.yawar.platform.tracing;

import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

/** Trace context kept in the logging MDC only; used when no Micrometer tracer is configured. */
public class MdcTraceContextProvider implements TraceContextProvider {

    @Override
    public TraceContext current() {
        String correlationId = Correlation.sanitize(MDC.get(Correlation.MDC_CORRELATION_ID));
        return new TraceContext(
                currentTraceparent(),
                correlationId != null ? correlationId : Correlation.newId(),
                MDC.get(Correlation.MDC_CAUSATION_ID));
    }

    /** Traceparent of the current thread, or {@code null}. Subclasses read it from the tracer. */
    protected String currentTraceparent() {
        return MDC.get(Correlation.MDC_TRACEPARENT);
    }

    @Override
    public Scope open(TraceContext received, String spanName) {
        String previousCorrelation = MDC.get(Correlation.MDC_CORRELATION_ID);
        String previousTraceparent = MDC.get(Correlation.MDC_TRACEPARENT);
        String previousCausation = MDC.get(Correlation.MDC_CAUSATION_ID);
        String correlationId = Correlation.sanitize(received.correlationId());
        put(Correlation.MDC_CORRELATION_ID, correlationId != null ? correlationId : Correlation.newId());
        put(Correlation.MDC_TRACEPARENT, received.traceparent());
        put(Correlation.MDC_CAUSATION_ID, received.causationId());
        return () -> {
            put(Correlation.MDC_CORRELATION_ID, previousCorrelation);
            put(Correlation.MDC_TRACEPARENT, previousTraceparent);
            put(Correlation.MDC_CAUSATION_ID, previousCausation);
        };
    }

    @Override
    public <T> Callable<T> propagate(Callable<T> task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContext(captured);
            try {
                return task.call();
            } finally {
                setContext(previous);
            }
        };
    }

    private static void setContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
