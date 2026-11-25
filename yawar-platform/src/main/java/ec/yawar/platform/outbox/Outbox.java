package ec.yawar.platform.outbox;

import java.util.UUID;

/**
 * Transactional Outbox (YAW-DOC-07 §9.2): records a message in the same transaction as the business
 * change. The relay publishes it after commit. Never publish to RabbitMQ directly from a use case.
 */
public interface Outbox {

    /**
     * Appends the message to the outbox table.
     *
     * @return the event id (also the envelope {@code eventId})
     * @throws IllegalStateException when called outside a transaction
     */
    UUID append(OutboxMessage message);
}
