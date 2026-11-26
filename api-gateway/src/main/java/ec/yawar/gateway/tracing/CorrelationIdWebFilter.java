package ec.yawar.gateway.tracing;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Starts the correlation of a request at the edge (RNF-12): a fresh UUID {@code X-Correlation-Id} that replaces any
 * value sent by the client (a client must not choose the id that appears in our logs), forwarded to the service,
 * returned in the response (also on 401/403/429/503) and put in the Reactor context for the JSON logs.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    /** Before Spring Security (order -100) so that rejected requests also carry the id. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = Correlation.newId();
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(Correlation.HTTP_HEADER, correlationId))
                .build();
        // beforeCommit runs after the proxied response headers were copied, so a service that echoes its own
        // X-Correlation-Id cannot add a second value.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(Correlation.HTTP_HEADER, correlationId);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build())
                .contextWrite(Context.of(Correlation.MDC_CORRELATION_ID, correlationId));
    }
}
