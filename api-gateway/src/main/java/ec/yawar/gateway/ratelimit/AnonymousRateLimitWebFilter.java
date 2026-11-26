package ec.yawar.gateway.ratelimit;

import ec.yawar.gateway.problem.Problems;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rate limit per IP for {@code /api/**} requests that carry no bearer token. The route limiter runs after Spring
 * Security, so without this filter anonymous callers (credential or path scanning, floods of 401) would never be
 * limited. It shares {@link ResilientRateLimiter} (60 requests/minute, Redis with local fallback) with key
 * {@code ip:<address>}.
 *
 * <p>Decisions: CORS preflights ({@code OPTIONS}) are exempt (browsers send one per request, they cost nothing and
 * a shared office IP must not lock its users out); requests with a bearer header are not counted here, they are
 * limited per user once authenticated, and the cost of invalid tokens is bounded by the signature check and the
 * throttled JWKS refresh ({@link ThrottledJwkSource}).
 */
@Component
public class AnonymousRateLimitWebFilter implements WebFilter, Ordered {

    /** After the correlation id (+10), before Spring Security (-100). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

    private final ResilientRateLimiter limiter;

    public AnonymousRateLimitWebFilter(ResilientRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!applies(exchange)) {
            return chain.filter(exchange);
        }
        return limiter.isAllowed("anonymous", "ip:" + ClientAddress.of(exchange))
                .flatMap(response -> {
                    if (response.isAllowed()) {
                        response.getHeaders().forEach(exchange.getResponse().getHeaders()::add);
                        return chain.filter(exchange);
                    }
                    return Problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                            "Too many requests: retry after the time given in Retry-After.", response.getHeaders());
                });
    }

    private static boolean applies(ServerWebExchange exchange) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/")
                || HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return false;
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }
}
