package ec.yawar.platform.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code yawar.platform.*} settings; defaults follow YAW-DOC-07 §9.4. */
@ConfigurationProperties("yawar.platform")
public record YawarPlatformProperties(
        @DefaultValue Outbox outbox,
        @DefaultValue Inbox inbox,
        @DefaultValue Consumer consumer,
        @DefaultValue Resilience resilience) {

    /**
     * @param relayEnabled   run the relay in this instance (disable in a service that only consumes)
     * @param pollInterval   pause between relay runs when the outbox is empty
     * @param batchSize      rows locked per run ({@code LIMIT} of the {@code SKIP LOCKED} select)
     * @param confirmTimeout max wait for the broker's publisher confirms of one batch
     * @param retention      how long published rows are kept (Debezium reads them before)
     * @param purgeInterval  how often published outbox rows and old inbox rows are deleted
     */
    public record Outbox(
            @DefaultValue("true") boolean relayEnabled,
            @DefaultValue("500ms") Duration pollInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("5s") Duration confirmTimeout,
            @DefaultValue("7d") Duration retention,
            @DefaultValue("1h") Duration purgeInterval) {
    }

    /** @param retention how long processed event ids are remembered for deduplication */
    public record Inbox(@DefaultValue("30d") Duration retention) {
    }

    /** @param maxAttempts deliveries before a message goes to the DLQ */
    public record Consumer(@DefaultValue("5") int maxAttempts) {
    }

    /**
     * @param timeout     default timeout of an external call
     * @param maxDeadline cap for the {@code X-Deadline-Ms} budget a client may ask for
     */
    public record Resilience(
            @DefaultValue("2s") Duration timeout,
            @DefaultValue("30s") Duration maxDeadline) {
    }
}
