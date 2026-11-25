package ec.yawar.platform.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExternalCallExecutorTest {

    private final ExternalCallExecutor executor = new ExternalCallExecutor(CircuitBreakerRegistry.ofDefaults(),
            RetryRegistry.ofDefaults(), BulkheadRegistry.ofDefaults(), Duration.ofMillis(300), Clock.systemUTC(),
            new ec.yawar.platform.tracing.MdcTraceContextProvider());

    @AfterEach
    void close() {
        executor.close();
    }

    @Test
    void returnsResultOfHealthyCall() {
        assertThat(executor.call("telegram", () -> "sent", e -> "fallback")).isEqualTo("sent");
    }

    @Test
    void idempotentCallIsRetriedUntilItSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        String result = executor.callIdempotent("ai-provider", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("503");
            }
            return "ok";
        }, e -> "fallback");

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void idempotentCallStopsAfterFourAttemptsAndUsesFallback() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> cause = new AtomicReference<>();

        String result = executor.callIdempotent("n8n", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        }, e -> {
            cause.set(e);
            return "queued";
        });

        assertThat(result).isEqualTo("queued");
        assertThat(calls).hasValue(4);
        assertThat(cause.get()).hasMessage("down");
    }

    @Test
    void nonIdempotentCallIsNeverRetried() {
        AtomicInteger calls = new AtomicInteger();

        String result = executor.call("smtp", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("timeout");
        }, e -> "email-later");

        assertThat(result).isEqualTo("email-later");
        assertThat(calls).hasValue(1);
    }

    @Test
    void slowCallTimesOut() {
        AtomicReference<Throwable> cause = new AtomicReference<>();

        String result = executor.call("telegram", () -> {
            sleep(2_000);
            return "late";
        }, e -> {
            cause.set(e);
            return "fallback";
        });

        assertThat(result).isEqualTo("fallback");
        assertThat(cause.get()).isInstanceOf(CallTimeoutException.class);
    }

    @Test
    void expiredDeadlineFailsFastWithoutCalling() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> cause = new AtomicReference<>();

        try (Deadline.Scope ignored = Deadline.bind(Deadline.in(Duration.ZERO, Clock.systemUTC()))) {
            executor.callIdempotent("ai-provider", () -> calls.incrementAndGet(), e -> {
                cause.set(e);
                return -1;
            });
        }

        assertThat(calls).hasValue(0);
        assertThat(cause.get()).isInstanceOf(DeadlineExceededException.class);
    }

    @Test
    void deadlineShorterThanTimeoutWins() {
        AtomicReference<Throwable> cause = new AtomicReference<>();
        long start = System.nanoTime();

        try (Deadline.Scope ignored = Deadline.bind(Deadline.in(Duration.ofMillis(50), Clock.systemUTC()))) {
            executor.call("telegram", () -> {
                sleep(1_000);
                return "late";
            }, e -> {
                cause.set(e);
                return "fallback";
            });
        }

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(290));
        assertThat(cause.get()).isInstanceOf(CallTimeoutException.class);
    }

    @Test
    void openCircuitShortCircuitsToFallback() {
        CircuitBreaker breaker = executor.circuitBreaker("telegram");
        breaker.transitionToOpenState();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> cause = new AtomicReference<>();

        executor.call("telegram", calls::incrementAndGet, e -> {
            cause.set(e);
            return 0;
        });

        assertThat(calls).hasValue(0);
        assertThat(cause.get()).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void defaultBreakerFollowsTheDocs() {
        CircuitBreaker breaker = executor.circuitBreaker("x");

        assertThat(breaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(20);
    }

    @Test
    void checkedExceptionFromCallIsWrapped() {
        AtomicReference<Throwable> cause = new AtomicReference<>();

        executor.call("x", () -> {
            throw new RuntimeException(new java.io.IOException("io"));
        }, e -> {
            cause.set(e);
            return null;
        });

        assertThat(cause.get()).hasRootCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void callRunsWithTheCallersCorrelationIdAndDeadline() {
        org.slf4j.MDC.put(ec.yawar.platform.tracing.Correlation.MDC_CORRELATION_ID, "req-42");
        Deadline deadline = Deadline.in(Duration.ofSeconds(5), Clock.systemUTC());
        try (Deadline.Scope ignored = Deadline.bind(deadline)) {
            String seen = executor.call("telegram", () -> org.slf4j.MDC.get("correlationId")
                    + "|" + Deadline.current().map(Deadline::expiresAt).orElse(null), e -> "fallback");

            assertThat(seen).isEqualTo("req-42|" + deadline.expiresAt());
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Test
    void fallbackIsMandatory() {
        assertThatThrownBy(() -> executor.call("x", () -> 1, null)).isInstanceOf(NullPointerException.class);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
