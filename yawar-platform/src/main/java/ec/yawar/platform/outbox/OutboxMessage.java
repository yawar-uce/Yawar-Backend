package ec.yawar.platform.outbox;

import ec.yawar.platform.envelope.Exchanges;
import java.util.Objects;

/**
 * A message to publish after the business transaction commits.
 *
 * @param exchange         {@link Exchanges#EVENTS} or {@link Exchanges#COMMANDS}
 * @param routingKey       event type for events, {@code <target-service>.<action>} for commands
 * @param eventType        envelope type, e.g. {@code donors.donor.registered}
 * @param aggregateType    e.g. {@code donor}
 * @param aggregateId      id of the aggregate (never a personal identifier such as an email)
 * @param aggregateVersion aggregate version after the change
 * @param schemaVersion    payload schema version (1 unless an incompatible change was made)
 * @param payload          object serialized as JSON; IDs only, no personal data (LOPDP)
 * @param causationId      id of the message or request that caused this one, may be {@code null}
 */
public record OutboxMessage(
        String exchange,
        String routingKey,
        String eventType,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        int schemaVersion,
        Object payload,
        String causationId) {

    public OutboxMessage {
        requireText(exchange, "exchange");
        requireText(routingKey, "routingKey");
        requireText(eventType, "eventType");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must be >= 0");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
    }

    /** Domain event to {@code yawar.events}; the routing key is the event type. */
    public static OutboxMessage event(String eventType, String aggregateType, String aggregateId,
                                      long aggregateVersion, Object payload) {
        return new OutboxMessage(Exchanges.EVENTS, eventType, eventType, aggregateType, aggregateId,
                aggregateVersion, 1, payload, null);
    }

    /** Command to {@code yawar.commands}; the routing key is {@code <target-service>.<action>}. */
    public static OutboxMessage command(String routingKey, String aggregateType, String aggregateId,
                                        long aggregateVersion, Object payload) {
        return new OutboxMessage(Exchanges.COMMANDS, routingKey, routingKey, aggregateType, aggregateId,
                aggregateVersion, 1, payload, null);
    }

    public OutboxMessage withCausationId(String newCausationId) {
        return new OutboxMessage(exchange, routingKey, eventType, aggregateType, aggregateId, aggregateVersion,
                schemaVersion, payload, newCausationId);
    }

    public OutboxMessage withSchemaVersion(int newSchemaVersion) {
        return new OutboxMessage(exchange, routingKey, eventType, aggregateType, aggregateId, aggregateVersion,
                newSchemaVersion, payload, causationId);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
