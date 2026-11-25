package ec.yawar.platform.tracing;

/**
 * Reads the current trace context (producer side) and restores a received one (consumer side).
 */
public interface TraceContextProvider {

    /** Trace context of the current thread; generates a correlation id if there is none. */
    TraceContext current();

    /**
     * Restores a received context for the duration of a message handling: MDC entries and, when a tracer
     * is available, a consumer span that is a child of {@code traceparent}.
     */
    Scope open(TraceContext received, String spanName);

    /** Closes what {@link #open} started; never throws. */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
