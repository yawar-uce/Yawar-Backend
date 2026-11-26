package ec.yawar.gateway.ratelimit;

import ec.yawar.gateway.problem.Problems;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The gateway's rate limit filter answers 429 with an empty body. This filter turns that response into a
 * {@code application/problem+json} one (headers such as {@code X-RateLimit-*} and {@code Retry-After} are kept).
 */
@Component
public class TooManyRequestsProblemWebFilter implements WebFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse original = exchange.getResponse();
        ServerHttpResponse decorated = new ServerHttpResponseDecorator(original) {
            @Override
            public Mono<Void> setComplete() {
                if (HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode()) && !isCommitted()) {
                    return Problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                            "Too many requests: retry after the time given in Retry-After.", Map.of());
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(decorated).build());
    }
}
