package ec.yawar.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/** Requests without a token are limited per IP before Spring Security runs (same 60 requests, strict count). */
@TestPropertySource(properties = {"yawar.gateway.rate-limit.burst=3600", "yawar.gateway.rate-limit.requested-tokens=60"})
class AnonymousRateLimitIT extends GatewayRedisITSupport {

    private EntityExchangeResult<byte[]> anonymous(String ip) {
        return client.get().uri("/api/v1/donors/me").header("X-Forwarded-For", ip).exchange().expectBody()
                .returnResult();
    }

    @Test
    void sixtyFirstRequestWithoutToken_returns429() {
        String ip = randomIp();

        for (int i = 1; i <= 60; i++) {
            assertThat(anonymous(ip).getStatus().value()).as("request %d", i).isEqualTo(401);
        }
        EntityExchangeResult<byte[]> denied = anonymous(ip);

        assertThat(denied.getStatus().value()).isEqualTo(429);
        assertThat(denied.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(denied.getResponseHeaders().getFirst("X-Correlation-Id")).isNotNull();
        assertThat(new String(denied.getResponseBody())).contains("\"status\":429");
        // Another address is not affected, and neither is an authenticated caller on the exhausted address.
        assertThat(anonymous(randomIp()).getStatus().value()).isEqualTo(401);
        getMe(donorToken()).header("X-Forwarded-For", ip).exchange().expectStatus().isOk();
    }

    @Test
    void preflight_isNotCounted() {
        String ip = randomIp();
        for (int i = 0; i < 70; i++) {
            client.options().uri("/api/v1/donors/me").header("X-Forwarded-For", ip)
                    .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
                    .exchange().expectStatus().isOk();
        }
    }
}
