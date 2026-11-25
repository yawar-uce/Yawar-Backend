package ec.yawar.platform.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes {@code X-Correlation-Id} from the request (set by the API Gateway) or creates one, puts it in the
 * MDC for the JSON logs and echoes it in the response. Invalid values are replaced, never logged as is.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    /** Before Spring Security (order -100) so 401/403 responses also carry the id. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = Correlation.sanitize(request.getHeader(Correlation.HTTP_HEADER));
        if (correlationId == null) {
            correlationId = Correlation.newId();
        }
        MDC.put(Correlation.MDC_CORRELATION_ID, correlationId);
        response.setHeader(Correlation.HTTP_HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Correlation.MDC_CORRELATION_ID);
        }
    }
}
