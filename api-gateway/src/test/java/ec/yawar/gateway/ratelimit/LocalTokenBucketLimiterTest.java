package ec.yawar.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LocalTokenBucketLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final LocalTokenBucketLimiter limiter = new LocalTokenBucketLimiter(1, 60, nanos::get);

    @Test
    void burstIsAllowedThenDenied() {
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryAcquire("user:a").allowed()).as("request %d", i + 1).isTrue();
        }
        var denied = limiter.tryAcquire("user:a");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
    }

    @Test
    void remainingDecreasesWithEachRequest() {
        assertThat(limiter.tryAcquire("user:a").remaining()).isEqualTo(59);
        assertThat(limiter.tryAcquire("user:a").remaining()).isEqualTo(58);
    }

    @Test
    void tokensComeBackAtTheReplenishRate() {
        for (int i = 0; i < 60; i++) {
            limiter.tryAcquire("user:a");
        }
        nanos.addAndGet(1_000_000_000L);

        assertThat(limiter.tryAcquire("user:a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("user:a").allowed()).isFalse();
    }

    @Test
    void bucketNeverExceedsTheBurst() {
        limiter.tryAcquire("user:a");
        nanos.addAndGet(3_600_000_000_000L);

        assertThat(limiter.tryAcquire("user:a").remaining()).isEqualTo(59);
    }

    @Test
    void usersHaveIndependentBuckets() {
        for (int i = 0; i < 61; i++) {
            limiter.tryAcquire("user:a");
        }

        assertThat(limiter.tryAcquire("user:a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("user:b").allowed()).isTrue();
    }

    @Test
    void publicConstructorUsesTheSystemClock() {
        assertThat(new LocalTokenBucketLimiter(1, 2).tryAcquire("k").allowed()).isTrue();
    }
}
