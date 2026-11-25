package ec.yawar.platform.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import ec.yawar.platform.tracing.TraceContextProvider;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs a call to an <b>external</b> dependency (Telegram, n8n, SMTP, the external AI model provider) with
 * the mechanisms of YAW-DOC-07 §9.4: timeout bounded by the request {@link Deadline}, bulkhead, circuit
 * breaker, retry with exponential backoff and jitter for idempotent calls only, and a mandatory fallback
 * (§9.5). It is not a way to call other Yawar services: those integrate through RabbitMQ (C-02).
 *
 * <p>The fallback receives every failure, including a timeout after which the remote side may still have
 * done the work (a message sent late): fallbacks of non-idempotent calls must tolerate that duplicate.
 *
 * <p>Instances are per dependency name; configure them with the usual {@code resilience4j.*} properties
 * (instance name = dependency). Without configuration the documented defaults apply: timeout 2 s, retry
 * 200/400/800 ms ± 20 %, circuit opens at 50 % failures over 20 calls and half-opens after 30 s.
 */
public class ExternalCallExecutor implements AutoCloseable {

    static final CircuitBreakerConfig DEFAULT_CIRCUIT_BREAKER = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .slidingWindowSize(20)
            .minimumNumberOfCalls(20)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .ignoreExceptions(DeadlineExceededException.class)
            .build();

    static final RetryConfig DEFAULT_RETRY = RetryConfig.custom()
            .maxAttempts(4) // first call + 3 retries
            .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200), 2.0, 0.2))
            .ignoreExceptions(DeadlineExceededException.class, CallNotPermittedException.class,
                    BulkheadFullException.class)
            .build();

    static final BulkheadConfig DEFAULT_BULKHEAD = BulkheadConfig.custom()
            .maxConcurrentCalls(20)
            .maxWaitDuration(Duration.ZERO)
            .build();

    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final BulkheadRegistry bulkheads;
    private final Duration defaultTimeout;
    private final Clock clock;
    private final TraceContextProvider traceContextProvider;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ExternalCallExecutor(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries,
                                BulkheadRegistry bulkheads, Duration defaultTimeout, Clock clock,
                                TraceContextProvider traceContextProvider) {
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.bulkheads = bulkheads;
        this.defaultTimeout = defaultTimeout;
        this.clock = clock;
        this.traceContextProvider = traceContextProvider;
    }

    /** Non-idempotent call (e.g. sending a message): never retried, only timeout + bulkhead + breaker. */
    public <T> T call(String dependency, Supplier<T> call, Function<Throwable, T> fallback) {
        return execute(dependency, call, fallback, false);
    }

    /** Idempotent call (e.g. a GET or a request with an idempotency key): also retried with jitter. */
    public <T> T callIdempotent(String dependency, Supplier<T> call, Function<Throwable, T> fallback) {
        return execute(dependency, call, fallback, true);
    }

    public CircuitBreaker circuitBreaker(String dependency) {
        return circuitBreakers.circuitBreaker(dependency, DEFAULT_CIRCUIT_BREAKER);
    }

    private <T> T execute(String dependency, Supplier<T> call, Function<Throwable, T> fallback, boolean idempotent) {
        Objects.requireNonNull(fallback, "every external dependency needs a fallback (YAW-DOC-07 §9.5)");
        Optional<Deadline> deadline = Deadline.current();

        Supplier<T> decorated = () -> withTimeout(dependency, call, deadline);
        decorated = Bulkhead.decorateSupplier(bulkheads.bulkhead(dependency, DEFAULT_BULKHEAD), decorated);
        decorated = CircuitBreaker.decorateSupplier(circuitBreaker(dependency), decorated);
        if (idempotent) {
            decorated = Retry.decorateSupplier(retries.retry(dependency, DEFAULT_RETRY), decorated);
        }
        try {
            return decorated.get();
        } catch (RuntimeException e) {
            return fallback.apply(e);
        }
    }

    private <T> T withTimeout(String dependency, Supplier<T> call, Optional<Deadline> deadline) {
        Duration timeout = defaultTimeout;
        if (deadline.isPresent()) {
            Duration remaining = deadline.get().remaining(clock);
            if (remaining.isZero()) {
                throw new DeadlineExceededException("deadline exceeded before calling " + dependency);
            }
            timeout = remaining.compareTo(timeout) < 0 ? remaining : timeout;
        }
        // The call runs on its own virtual thread: carry the MDC, the current span and the deadline along, so
        // the adapter's logs and outgoing traceparent belong to the request.
        Deadline bound = deadline.orElse(null);
        Future<T> future = executor.submit(traceContextProvider.propagate(() -> {
            if (bound == null) {
                return call.get();
            }
            try (Deadline.Scope ignored = Deadline.bind(bound)) {
                return call.get();
            }
        }));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new CallTimeoutException(dependency + " did not answer within " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(dependency + " call failed", e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CallTimeoutException(dependency + " call interrupted");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
