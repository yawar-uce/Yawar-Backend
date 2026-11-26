package ec.yawar.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import reactor.core.publisher.Mono;

class ResilientRateLimiterTest {

    private final RedisRateLimiter redis = mock(RedisRateLimiter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RateLimitProperties properties = new RateLimitProperties(1, 3L, null, Duration.ofMillis(100));
    private final ResilientRateLimiter limiter = new ResilientRateLimiter(redis,
            new LocalTokenBucketLimiter(1, 3), properties, meters);

    @Test
    void redisAnswer_isUsedAsIs() {
        var answer = new Response(true, Map.of("X-RateLimit-Remaining", "41"));
        when(redis.isAllowed("r", "user:a")).thenReturn(Mono.just(answer));

        assertThat(limiter.isAllowed("r", "user:a").block()).isSameAs(answer);
        assertThat(meters.get("yawar.gateway.ratelimit.fallback").counter().count()).isZero();
    }

    @Test
    void redisDenial_carriesRetryAfter() {
        when(redis.isAllowed("r", "user:a"))
                .thenReturn(Mono.just(new Response(false, Map.of("X-RateLimit-Remaining", "0"))));

        var response = limiter.isAllowed("r", "user:a").block();

        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getHeaders()).containsEntry("Retry-After", "1").containsEntry("X-RateLimit-Remaining", "0");
    }

    @Test
    void redisFailOpenAnswer_isReplacedByTheLocalLimit() {
        // RedisRateLimiter answers allowed=true with remaining -1 when its Lua call fails.
        when(redis.isAllowed("r", "user:a"))
                .thenReturn(Mono.just(new Response(true, Map.of("X-RateLimit-Remaining", "-1"))));

        var first = limiter.isAllowed("r", "user:a").block();

        assertThat(first.isAllowed()).isTrue();
        assertThat(first.getHeaders()).containsEntry("X-RateLimit-Remaining", "2")
                .containsEntry("X-RateLimit-Burst-Capacity", "3").containsEntry("X-RateLimit-Replenish-Rate", "1");
        assertThat(meters.get("yawar.gateway.ratelimit.fallback").counter().count()).isEqualTo(1);
    }

    @Test
    void redisError_fallsBackToLocalLimitThatEventuallyDenies() {
        when(redis.isAllowed(eq("r"), anyString())).thenReturn(Mono.error(new IllegalStateException("connection refused")));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.isAllowed("r", "user:a").block().isAllowed()).isTrue();
        }
        var denied = limiter.isAllowed("r", "user:a").block();

        assertThat(denied.isAllowed()).isFalse();
        assertThat(denied.getHeaders()).containsEntry("Retry-After", "1");
        assertThat(limiter.isAllowed("r", "user:other").block().isAllowed()).isTrue();
    }

    @Test
    void redisTimeout_fallsBackToLocalLimit() {
        when(redis.isAllowed("r", "user:a")).thenReturn(Mono.never());

        assertThat(limiter.isAllowed("r", "user:a").block(Duration.ofSeconds(2)).isAllowed()).isTrue();
        assertThat(meters.get("yawar.gateway.ratelimit.fallback").counter().count()).isEqualTo(1);
    }

    @Test
    void defaults_are60PerMinute() {
        var defaults = new RateLimitProperties(null, null, null, null);

        assertThat(defaults.replenishPerSecond()).isEqualTo(1);
        assertThat(defaults.burst()).isEqualTo(60);
        assertThat(defaults.requestedTokens()).isEqualTo(1);
        assertThat(defaults.redisTimeout()).isEqualTo(Duration.ofMillis(500));
    }
}
