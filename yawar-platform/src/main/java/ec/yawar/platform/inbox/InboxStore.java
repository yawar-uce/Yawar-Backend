package ec.yawar.platform.inbox;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Idempotent-consumer table: one row per (eventId, consumer). */
public class InboxStore {

    private static final String INSERT = """
            INSERT INTO inbox (event_id, consumer, event_type) VALUES (:eventId, :consumer, :eventType)
            ON CONFLICT (event_id, consumer) DO NOTHING
            """;

    private final JdbcClient jdbc;

    public InboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event for this consumer inside the current transaction.
     *
     * @return {@code true} the first time, {@code false} for a duplicate delivery
     */
    public boolean markReceived(UUID eventId, String consumer, String eventType) {
        return jdbc.sql(INSERT)
                .param("eventId", eventId)
                .param("consumer", consumer)
                .param("eventType", eventType)
                .update() == 1;
    }
}
