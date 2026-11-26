package ec.yawar.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * Each request costs 60 of 3600 tokens refilled at 1 token/s: 60 requests, then one more only after a minute, so the
 * refill cannot disturb the strict count (production: cost 1, burst 60, same 60 requests).
 */
@TestPropertySource(properties = {"yawar.gateway.rate-limit.burst=3600", "yawar.gateway.rate-limit.requested-tokens=60"})
class RateLimitIT extends GatewayRedisITSupport {

    private int status(String token) {
        EntityExchangeResult<byte[]> result = getMe(token).exchange().expectBody().returnResult();
        return result.getStatus().value();
    }

    @Test
    void sixtyFirstRequestWithinMinute_returns429() {
        String token = donorToken();

        for (int i = 1; i <= 60; i++) {
            assertThat(status(token)).as("request %d", i).isEqualTo(200);
        }

        assertThat(status(token)).as("request 61").isEqualTo(429);
    }

    @Test
    void rejectedRequest_carriesRateLimitHeadersAndProblemBody() {
        String token = donorToken();
        for (int i = 0; i < 60; i++) {
            status(token);
        }

        EntityExchangeResult<byte[]> denied = getMe(token).exchange().expectBody().returnResult();

        assertThat(denied.getStatus().value()).isEqualTo(429);
        // Fewer tokens than one request costs (a few may have been refilled while the test ran).
        assertThat(Long.parseLong(denied.getResponseHeaders().getFirst("X-RateLimit-Remaining"))).isLessThan(60);
        assertThat(denied.getResponseHeaders().getFirst("X-RateLimit-Burst-Capacity")).isEqualTo("3600");
        assertThat(denied.getResponseHeaders().getFirst("X-RateLimit-Replenish-Rate")).isEqualTo("1");
        assertThat(denied.getResponseHeaders().getFirst("X-RateLimit-Requested-Tokens")).isEqualTo("60");
        assertThat(denied.getResponseHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(denied.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(denied.getResponseBody())).contains("\"status\":429");
    }

    @Test
    void otherUser_notAffected_returns200() {
        String exhausted = donorToken();
        for (int i = 0; i < 65; i++) {
            status(exhausted);
        }

        assertThat(status(donorToken())).isEqualTo(200);
    }

    @Test
    void allowedResponse_exposesRemainingTokens() {
        getMe(donorToken()).exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "3540")
                .expectHeader().valueEquals("X-RateLimit-Burst-Capacity", "3600");
    }
}
