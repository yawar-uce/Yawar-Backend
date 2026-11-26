package ec.yawar.gateway.resilience;

import ec.yawar.gateway.problem.Problems;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.Map;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Fallback of the circuit breaker filters ({@code fallbackUri: forward:/fallback/<service>}): a 503 problem+json, with
 * {@code Retry-After} while the circuit is open. The forward does not pass through the security chain, which denies the path to clients.
 */
@RestController
class FallbackController {

    private final CircuitBreakerProperties properties;

    FallbackController(CircuitBreakerProperties properties) {
        this.properties = properties;
    }

    @RequestMapping("/fallback/ms-donors")
    Mono<Void> msDonors(ServerWebExchange exchange) {
        // Retry-After only when the circuit is open (the next attempt will not be tried before then); a single
        // failed call gives no such promise.
        boolean open = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR)
                instanceof CallNotPermittedException;
        Map<String, String> headers = open
                ? Map.of(HttpHeaders.RETRY_AFTER, Long.toString(properties.retryAfterSeconds())) : Map.of();
        return Problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                "The donors service is temporarily unavailable. Try again shortly.", headers);
    }
}
