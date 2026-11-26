package ec.yawar.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

class UserOrIpKeyResolverTest {

    private final UserOrIpKeyResolver resolver = new UserOrIpKeyResolver();

    private static MockServerHttpRequest.BaseBuilder<?> request() {
        return MockServerHttpRequest.get("/api/v1/donors/me").remoteAddress(new InetSocketAddress("10.0.0.9", 5555));
    }

    private static JwtAuthenticationToken token(String subject) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).claim("scope", "x");
        if (subject != null) {
            jwt.subject(subject);
        }
        return new JwtAuthenticationToken(jwt.build(), List.of());
    }

    @Test
    void authenticatedCaller_isKeyedBySubject() {
        var exchange = MockServerWebExchange.from(request().build()).mutate()
                .principal(Mono.just(token("sub-123"))).build();

        assertThat(resolver.resolve(exchange).block()).isEqualTo("user:sub-123");
    }

    @Test
    void anonymousCaller_isKeyedByTcpPeer() {
        var exchange = MockServerWebExchange.from(request().build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:10.0.0.9");
    }

    @Test
    void forwardedFor_usesTheEntryAppendedByTheTrustedProxy() {
        // "6.6.6.6" is what the client claims; the Ingress appended the real address last.
        var exchange = MockServerWebExchange.from(
                request().header("X-Forwarded-For", "6.6.6.6, 203.0.113.7").build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:203.0.113.7");
    }

    @Test
    void tokenWithoutSubject_fallsBackToIp() {
        var exchange = MockServerWebExchange.from(request().build()).mutate().principal(Mono.just(token(null))).build();

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:10.0.0.9");
    }

    @Test
    void nonJwtPrincipal_fallsBackToIp() {
        var exchange = MockServerWebExchange.from(request().build()).mutate()
                .principal(Mono.just(new UsernamePasswordAuthenticationToken("u", "p"))).build();

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:10.0.0.9");
    }

    @Test
    void unknownPeer_isKeyedAsUnknown() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());

        assertThat(resolver.resolve(exchange).block()).isEqualTo("ip:unknown");
    }
}
