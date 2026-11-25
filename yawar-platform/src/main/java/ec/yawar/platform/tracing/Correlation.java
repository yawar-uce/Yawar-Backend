package ec.yawar.platform.tracing;

import java.util.UUID;
import java.util.regex.Pattern;

/** Header names, MDC keys and validation for the correlation id (RNF-12). */
public final class Correlation {

    /** HTTP header set by the API Gateway and echoed in responses. */
    public static final String HTTP_HEADER = "X-Correlation-Id";

    /** AMQP header (lower case, as AMQP headers are case sensitive). */
    public static final String AMQP_HEADER = "x-correlation-id";

    /** W3C trace context header, the same name in HTTP and AMQP. */
    public static final String TRACEPARENT = "traceparent";

    /** MDC key used by the JSON log layout. */
    public static final String MDC_CORRELATION_ID = "correlationId";

    /** MDC key with the id of the message being handled; becomes the causationId of what it publishes. */
    public static final String MDC_CAUSATION_ID = "causationId";

    /** MDC key for the traceparent when no tracer is present. */
    public static final String MDC_TRACEPARENT = "traceparent";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private Correlation() {
    }

    /** Returns the value if it is a safe correlation id (no log injection), otherwise {@code null}. */
    public static String sanitize(String value) {
        return value != null && VALID.matcher(value).matches() ? value : null;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
