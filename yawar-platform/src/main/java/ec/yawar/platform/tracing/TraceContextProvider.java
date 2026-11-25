package ec.yawar.platform.tracing;

import java.util.concurrent.Callable;

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

    /**
     * Wraps a task so that it runs on another thread with the caller's MDC (correlation id) and, when a tracer
     * is available, the caller's current span.
     */
    <T> Callable<T> propagate(Callable<T> task);

    /** Closes what {@link #open} started; never throws. */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
