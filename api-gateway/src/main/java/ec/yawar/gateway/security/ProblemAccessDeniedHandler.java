package ec.yawar.gateway.security;

import ec.yawar.gateway.problem.Problems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 403 as problem+json: the token is valid but the roles do not allow the route. */
public class ProblemAccessDeniedHandler implements ServerAccessDeniedHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return Problems.write(exchange, HttpStatus.FORBIDDEN, "You do not have permission to access this resource.",
                Map.of());
    }
}
