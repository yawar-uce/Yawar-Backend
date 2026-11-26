package ec.yawar.gateway.resilience;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Circuit breaker and time limiter of the {@code ms-donors} route (YAW-DOC-07 §9.4): opens at 50 % failures in a
 * window of 20 calls, stays open 30 s, then lets 3 trial calls through; a call longer than 2 s counts as failed.
 */
@ConfigurationProperties("yawar.gateway.circuit-breaker")
public record CircuitBreakerProperties(Integer slidingWindowSize, Float failureRateThreshold, Duration waitInOpenState,
                                       Integer halfOpenCalls, Duration timeLimit) {

    public CircuitBreakerProperties {
        slidingWindowSize = slidingWindowSize == null ? 20 : slidingWindowSize;
        failureRateThreshold = failureRateThreshold == null ? 50f : failureRateThreshold;
        waitInOpenState = waitInOpenState == null ? Duration.ofSeconds(30) : waitInOpenState;
        halfOpenCalls = halfOpenCalls == null ? 3 : halfOpenCalls;
        timeLimit = timeLimit == null ? Duration.ofSeconds(2) : timeLimit;
    }

    /** Seconds a client should wait before retrying while the circuit is open. */
    public long retryAfterSeconds() {
        return waitInOpenState.toSeconds();
    }
}
