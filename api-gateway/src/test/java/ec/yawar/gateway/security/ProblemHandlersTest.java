package ec.yawar.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import ec.yawar.gateway.tracing.Correlation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProblemHandlersTest {

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/donors/me")
                .header(Correlation.HTTP_HEADER, "corr-1").build());
    }

    private static JsonNode body(MockServerWebExchange exchange) {
        return JsonMapper.builder().build().readTree(exchange.getResponse().getBodyAsString().block());
    }

    @Test
    void invalidToken_returns401ProblemWithInvalidTokenChallenge() {
        var exchange = exchange();

        new ProblemAuthenticationEntryPoint().commence(exchange, new InvalidBearerTokenException("Jwt expired at 2026-01-01")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = body(exchange);
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("title").asString()).isEqualTo("Unauthorized");
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/donors/me");
        assertThat(body.get("correlationId").asString()).isEqualTo("corr-1");
        // The reason of the failure is never revealed.
        assertThat(body.get("detail").asString()).doesNotContain("Jwt expired");
    }

    @Test
    void missingToken_returns401WithBareBearerChallenge() {
        var exchange = exchange();

        new ProblemAuthenticationEntryPoint()
                .commence(exchange, new InsufficientAuthenticationException("no credentials")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    void otherAuthenticationFailure_returns401WithBareBearerChallenge() {
        var exchange = exchange();

        new ProblemAuthenticationEntryPoint().commence(exchange, new BadCredentialsException("x")).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    void accessDenied_returns403Problem() {
        var exchange = exchange();

        new ProblemAccessDeniedHandler().handle(exchange, new AccessDeniedException("Access Denied")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(body(exchange).get("status").asInt()).isEqualTo(403);
    }
}
