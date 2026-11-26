package ec.yawar.gateway.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limit per user (C-07, YAW-DOC-07 §9.4): token bucket refilled at {@code replenishPerSecond} with a capacity of
 * {@code burst}; 1 per second and 60 means 60 requests per minute sustained. Each request takes
 * {@code requestedTokens} (1 in production; the tests raise it to make the refill negligible). {@code redisTimeout} bounds the call to
 * Redis; beyond it the local in-memory bucket decides.
 */
@ConfigurationProperties("yawar.gateway.rate-limit")
public record RateLimitProperties(Integer replenishPerSecond, Long burst, Integer requestedTokens,
                                  Duration redisTimeout) {

    public RateLimitProperties {
        replenishPerSecond = replenishPerSecond == null ? 1 : replenishPerSecond;
        burst = burst == null ? 60L : burst;
        requestedTokens = requestedTokens == null ? 1 : requestedTokens;
        redisTimeout = redisTimeout == null ? Duration.ofMillis(500) : redisTimeout;
    }
}
