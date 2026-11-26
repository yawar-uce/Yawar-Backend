package ec.yawar.gateway.security;

import ec.yawar.gateway.problem.Problems;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 401 as problem+json. With a token that failed validation {@code WWW-Authenticate} carries
 * {@code error="invalid_token"} (RFC 6750 §3); without any token it is the bare {@code Bearer} challenge. The
 * reason of the failure (expired, wrong audience…) is not revealed to the client.
 */
public class ProblemAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        boolean invalidToken = ex instanceof OAuth2AuthenticationException;
        String challenge = invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer";
        String detail = invalidToken ? "The access token is invalid or expired." : "Authentication is required.";
        return Problems.write(exchange, HttpStatus.UNAUTHORIZED, detail,
                Map.of(HttpHeaders.WWW_AUTHENTICATE, challenge));
    }
}
