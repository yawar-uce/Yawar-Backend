package ec.yawar.platform.envelope;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Standard Yawar message envelope shared by every event and command (YAW-DOC-07 §8.3).
 *
 * <p>The payload carries identifiers only, never personal data of a donor (LOPDP). Evolution is additive;
 * an incompatible change increments {@code schemaVersion}.
 *
 * @param eventId          unique id, used by consumers for deduplication (inbox)
 * @param eventType        routing-key style type, e.g. {@code donors.donor.registered}
 * @param aggregateId      id of the aggregate that produced the message
 * @param aggregateVersion version of the aggregate after the change (projections ignore older ones)
 * @param occurredAt       when the change was committed
 * @param schemaVersion    payload schema version, starts at 1
 * @param correlationId    id of the originating request, kept across services
 * @param causationId      id of the message or request that caused this one (nullable)
 * @param traceparent      W3C trace context of the producing span (nullable)
 * @param producer         name of the producing service, e.g. {@code ms-donors}
 * @param payload          event-specific JSON body
 */
public record Envelope(
        UUID eventId,
        String eventType,
        String aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        int schemaVersion,
        String correlationId,
        String causationId,
        String traceparent,
        String producer,
        JsonNode payload) {

    public Envelope {
        Objects.requireNonNull(eventId, "eventId");
        requireText(eventType, "eventType");
        requireText(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(correlationId, "correlationId");
        requireText(producer, "producer");
        Objects.requireNonNull(payload, "payload");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must be >= 0");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
