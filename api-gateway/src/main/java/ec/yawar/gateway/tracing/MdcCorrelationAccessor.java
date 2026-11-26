package ec.yawar.gateway.tracing;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * Copies the correlation id from the Reactor context to the MDC around operators (automatic context propagation),
 * so every log line of a request carries {@code correlationId} whichever thread handles it.
 */
public class MdcCorrelationAccessor implements ThreadLocalAccessor<String> {

    @Override
    public Object key() {
        return Correlation.MDC_CORRELATION_ID;
    }

    @Override
    public String getValue() {
        return MDC.get(Correlation.MDC_CORRELATION_ID);
    }

    @Override
    public void setValue(String value) {
        MDC.put(Correlation.MDC_CORRELATION_ID, value);
    }

    @Override
    public void setValue() {
        MDC.remove(Correlation.MDC_CORRELATION_ID);
    }
}
