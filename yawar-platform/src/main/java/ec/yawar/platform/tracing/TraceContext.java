package ec.yawar.platform.tracing;

/**
 * Trace data carried by a message: W3C {@code traceparent}, the request {@code correlationId} and the
 * {@code causationId} (the message or request that caused this one).
 */
public record TraceContext(String traceparent, String correlationId, String causationId) {

    public TraceContext withCausationId(String newCausationId) {
        return new TraceContext(traceparent, correlationId, newCausationId);
    }
}
