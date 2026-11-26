package ec.yawar.gateway.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.AbstractStatefulConfigurable;
import reactor.core.publisher.Mono;

/**
 * Redis rate limiter with the fallback of YAW-DOC-07 §9.5. {@link RedisRateLimiter} is fail-open: when the Lua call
 * fails it logs the error and answers {@code allowed=true} with {@code X-RateLimit-Remaining: -1}. Here that answer
 * (or an error or timeout) is discarded and the local {@link LocalTokenBucketLimiter} decides instead, so a Redis
 * outage degrades to a per-pod limit and never to "no limit". Denied responses carry {@code Retry-After}.
 */
public class ResilientRateLimiter extends AbstractStatefulConfigurable<Object> implements RateLimiter<Object> {

    private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);
    private static final long LOG_EVERY_MS = 30_000;

    private final RedisRateLimiter redis;
    private final LocalTokenBucketLimiter local;
    private final Duration redisTimeout;
    private final int replenishPerSecond;
    private final long burst;
    private final int cost;
    private final Counter fallbackCounter;
    private final AtomicLong lastLogged = new AtomicLong();

    public ResilientRateLimiter(RedisRateLimiter redis, LocalTokenBucketLimiter local, RateLimitProperties properties,
                                MeterRegistry meters) {
        super(Object.class);
        this.redis = redis;
        this.local = local;
        this.redisTimeout = properties.redisTimeout();
        this.replenishPerSecond = properties.replenishPerSecond();
        this.burst = properties.burst();
        this.cost = properties.requestedTokens();
        this.fallbackCounter = Counter.builder("yawar.gateway.ratelimit.fallback")
                .description("Requests decided by the local limiter because Redis was unavailable")
                .register(meters);
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String key) {
        return redis.isAllowed(routeId, key)
                .timeout(redisTimeout)
                .filter(response -> !isRedisFailure(response))
                .onErrorResume(error -> Mono.empty())
                .switchIfEmpty(Mono.fromSupplier(() -> decideLocally(key)))
                .map(this::withRetryAfter);
    }

    private static boolean isRedisFailure(Response response) {
        return "-1".equals(response.getHeaders().get(RedisRateLimiter.REMAINING_HEADER));
    }

    private Response decideLocally(String key) {
        fallbackCounter.increment();
        long now = System.currentTimeMillis();
        long previous = lastLogged.get();
        if (now - previous > LOG_EVERY_MS && lastLogged.compareAndSet(previous, now)) {
            log.warn("Redis rate limiter unavailable: using the local per-pod limit");
        }
        LocalTokenBucketLimiter.Decision decision = local.tryAcquire(key);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(RedisRateLimiter.REMAINING_HEADER, Long.toString(decision.remaining()));
        headers.put(RedisRateLimiter.REPLENISH_RATE_HEADER, Integer.toString(replenishPerSecond));
        headers.put(RedisRateLimiter.BURST_CAPACITY_HEADER, Long.toString(burst));
        headers.put(RedisRateLimiter.REQUESTED_TOKENS_HEADER, Integer.toString(cost));
        return new Response(decision.allowed(), headers);
    }

    private Response withRetryAfter(Response response) {
        if (response.isAllowed()) {
            return response;
        }
        Map<String, String> headers = new LinkedHashMap<>(response.getHeaders());
        // The tokens of one request are back after cost / replenishRate seconds (rounded up).
        headers.put("Retry-After", Long.toString(Math.max(1, (long) Math.ceil((double) cost / replenishPerSecond))));
        return new Response(false, headers);
    }
}
