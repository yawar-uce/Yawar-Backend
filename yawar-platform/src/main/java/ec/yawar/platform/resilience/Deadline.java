package ec.yawar.platform.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Time budget of the current request. The caller sends the remaining budget in {@code X-Deadline-Ms}
 * (milliseconds); every external call made while serving the request uses at most what is left.
 */
public record Deadline(Instant expiresAt) {

    /** HTTP header with the remaining budget in milliseconds. */
    public static final String HEADER = "X-Deadline-Ms";

    private static final ThreadLocal<Deadline> CURRENT = new ThreadLocal<>();

    public static Deadline in(Duration budget, Clock clock) {
        return new Deadline(clock.instant().plus(budget));
    }

    public Duration remaining(Clock clock) {
        Duration left = Duration.between(clock.instant(), expiresAt);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public boolean isExpired(Clock clock) {
        return !clock.instant().isBefore(expiresAt);
    }

    /** Deadline of the current thread (set by {@link DeadlineFilter} or {@link #bind}). */
    public static Optional<Deadline> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Binds the deadline to the current thread until the returned scope is closed. */
    public static Scope bind(Deadline deadline) {
        Deadline previous = CURRENT.get();
        CURRENT.set(deadline);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** Closes a {@link #bind}; never throws. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
