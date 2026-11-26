package ec.yawar.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/** Redis is unreachable (nothing listens on port 1): the gateway keeps limiting per pod instead of failing open. */
@TestPropertySource(properties = {"spring.data.redis.port=1", "spring.data.redis.host=127.0.0.1",
        "yawar.gateway.rate-limit.burst=3600", "yawar.gateway.rate-limit.requested-tokens=60"})
class RateLimitRedisDownIT extends GatewayITSupport {

    private int status(String token) {
        EntityExchangeResult<byte[]> result = getMe(token).exchange().expectBody().returnResult();
        return result.getStatus().value();
    }

    @Test
    void redisDown_fallsBackToLocalLimit() {
        String token = donorToken();

        for (int i = 1; i <= 60; i++) {
            assertThat(status(token)).as("request %d", i).isEqualTo(200);
        }

        assertThat(status(token)).as("request 61").isEqualTo(429);
    }

    @Test
    void redisDown_anonymousCallersAreLimitedLocallyToo() {
        String ip = randomIp();
        int last = 0;
        for (int i = 0; i < 61; i++) {
            last = client.get().uri("/api/v1/donors/me").header("X-Forwarded-For", ip).exchange().expectBody()
                    .returnResult().getStatus().value();
        }

        assertThat(last).isEqualTo(429);
    }

    @Test
    void redisDown_doesNotAffectReadiness() {
        client.get().uri("/health/ready").exchange().expectStatus().isOk();
    }

    @Test
    void redisDown_otherUserStillServed() {
        assertThat(status(donorToken())).isEqualTo(200);
    }
}
