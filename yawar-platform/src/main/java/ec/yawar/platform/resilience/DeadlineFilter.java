package ec.yawar.platform.resilience;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code X-Deadline-Ms} and binds a {@link Deadline} for the request. Missing or invalid values mean
 * "no deadline"; budgets above {@code maxBudget} are capped so a client cannot hold resources forever.
 */
public class DeadlineFilter extends OncePerRequestFilter implements Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 11;

    private final Clock clock;
    private final Duration maxBudget;

    public DeadlineFilter(Clock clock, Duration maxBudget) {
        this.clock = clock;
        this.maxBudget = maxBudget;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Duration budget = parse(request.getHeader(Deadline.HEADER));
        if (budget == null) {
            chain.doFilter(request, response);
            return;
        }
        try (Deadline.Scope ignored = Deadline.bind(Deadline.in(budget, clock))) {
            chain.doFilter(request, response);
        }
    }

    private Duration parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long millis = Long.parseLong(header.trim());
            if (millis < 0) {
                return null;
            }
            Duration budget = Duration.ofMillis(millis);
            return budget.compareTo(maxBudget) > 0 ? maxBudget : budget;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
