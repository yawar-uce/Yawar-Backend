package ec.yawar.gateway.tracing;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation id constants. The pattern and names are the same as {@code ec.yawar.platform.tracing.Correlation}
 * (the gateway does not depend on yawar-platform, which is servlet and JDBC based); keep both in step.
 */
public final class Correlation {

    public static final String HTTP_HEADER = "X-Correlation-Id";

    /** MDC key and Reactor context key; the JSON logs print every MDC entry. */
    public static final String MDC_CORRELATION_ID = "correlationId";

    public static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private Correlation() {
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
