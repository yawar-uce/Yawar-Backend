package ec.yawar.platform.outbox;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the outbox relay every {@code pollInterval} and the housekeeping once per {@code purgeInterval} on a
 * dedicated thread, without requiring {@code @EnableScheduling} in the service.
 */
public class PlatformScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PlatformScheduler.class);

    private final OutboxRelay relay;
    private final Housekeeping housekeeping;
    private final Duration pollInterval;
    private final Duration purgeInterval;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    /** {@code relay} or {@code housekeeping} may be {@code null} when disabled. */
    public PlatformScheduler(OutboxRelay relay, Housekeeping housekeeping, Duration pollInterval,
                             Duration purgeInterval) {
        this.relay = relay;
        this.housekeeping = housekeeping;
        this.pollInterval = pollInterval;
        this.purgeInterval = purgeInterval;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        if (relay != null) {
            relay.verifyPublisherConfirms();   // before creating the thread: nothing leaks if it fails
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("yawar-outbox").daemon().factory());
        if (relay != null) {
            executor.scheduleWithFixedDelay(this::relay, pollInterval.toMillis(), pollInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
        if (housekeeping != null) {
            executor.scheduleWithFixedDelay(this::purge, purgeInterval.toMillis(), purgeInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    // A failed run must not cancel the schedule: log it and try again on the next tick.
    private void relay() {
        try {
            int published;
            do {
                published = relay.relayOnce();
            } while (published > 0 && running && !Thread.currentThread().isInterrupted());
        } catch (RuntimeException e) {
            log.error("Outbox relay run failed", e);
        }
    }

    private void purge() {
        try {
            int deleted = housekeeping.purge();
            log.info("Platform housekeeping deleted {} outbox/inbox rows", deleted);
        } catch (RuntimeException e) {
            log.error("Platform housekeeping failed", e);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
