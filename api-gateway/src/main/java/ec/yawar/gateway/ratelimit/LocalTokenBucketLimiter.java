package ec.yawar.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * In-memory token bucket per key, same limits as the Redis limiter. It is the fallback when Redis is unreachable
 * (YAW-DOC-07 §9.5): every pod counts on its own, so the effective limit is up to {@code replicas x burst} until
 * Redis returns, which is still bounded (the alternative, no limit, is not).
 */
public class LocalTokenBucketLimiter {

    /** Result of one attempt; {@code remaining} is the whole tokens left. */
    public record Decision(boolean allowed, long remaining) {
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int replenishPerSecond;
    private final long burst;
    private final int cost;
    private final LongSupplier nanoClock;
    private final Cache<String, Bucket> buckets;

    public LocalTokenBucketLimiter(int replenishPerSecond, long burst) {
        this(replenishPerSecond, burst, 1, System::nanoTime);
    }

    public LocalTokenBucketLimiter(int replenishPerSecond, long burst, int cost) {
        this(replenishPerSecond, burst, cost, System::nanoTime);
    }

    LocalTokenBucketLimiter(int replenishPerSecond, long burst, LongSupplier nanoClock) {
        this(replenishPerSecond, burst, 1, nanoClock);
    }

    LocalTokenBucketLimiter(int replenishPerSecond, long burst, int cost, LongSupplier nanoClock) {
        this.cost = cost;
        this.replenishPerSecond = replenishPerSecond;
        this.burst = burst;
        this.nanoClock = nanoClock;
        // A bucket idle for longer than the time to fill is full again, so it can be forgotten.
        Duration idle = Duration.ofSeconds(Math.max(60, burst / Math.max(1, replenishPerSecond) + 5));
        this.buckets = Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(idle).build();
    }

    public Decision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        return buckets.get(key, k -> new Bucket(burst, now)).tryTake(now);
    }

    private final class Bucket {
        private double tokens;
        private long lastRefill;

        Bucket(long initial, long now) {
            this.tokens = initial;
            this.lastRefill = now;
        }

        synchronized Decision tryTake(long now) {
            double refill = (now - lastRefill) / (double) NANOS_PER_SECOND * replenishPerSecond;
            tokens = Math.min(burst, tokens + Math.max(0, refill));
            lastRefill = now;
            if (tokens >= cost) {
                tokens -= cost;
                return new Decision(true, (long) tokens);
            }
            return new Decision(false, 0);
        }
    }
}
