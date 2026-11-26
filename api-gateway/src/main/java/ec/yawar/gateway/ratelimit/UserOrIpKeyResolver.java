package ec.yawar.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rate limit key of authenticated requests: {@code user:<sub>} (see {@link ClientAddress} for the address fallback,
 * which is a safety net: requests without a valid token never get this far, they are limited per IP by
 * {@link AnonymousRateLimitWebFilter}).
 */
public class UserOrIpKeyResolver implements KeyResolver {

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .mapNotNull(token -> token.getToken().getSubject())
                .map(subject -> "user:" + subject)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + ClientAddress.of(exchange)));
    }
}
