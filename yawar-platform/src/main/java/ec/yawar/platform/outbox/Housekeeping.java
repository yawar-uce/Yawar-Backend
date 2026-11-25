package ec.yawar.platform.outbox;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purges published outbox rows and old inbox rows (YAW-DOC-07 §9.2: "an extra table … purged periodically").
 * Only one replica purges at a time ({@code pg_try_advisory_xact_lock}); rows are deleted in batches so a
 * long backlog never becomes one huge statement.
 */
public class Housekeeping {

    static final int BATCH = 5000;
    private static final String LOCK = "SELECT pg_try_advisory_xact_lock(hashtext('yawar-platform-housekeeping'))";
    private static final String PURGE_OUTBOX = """
            DELETE FROM outbox WHERE id IN (
                SELECT id FROM outbox WHERE published_at < now() - make_interval(secs => :seconds) LIMIT :batch)
            """;
    private static final String PURGE_INBOX = """
            DELETE FROM inbox WHERE (event_id, consumer) IN (
                SELECT event_id, consumer FROM inbox
                WHERE received_at < now() - make_interval(secs => :seconds) LIMIT :batch)
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;
    private final Duration outboxRetention;
    private final Duration inboxRetention;

    public Housekeeping(JdbcClient jdbc, TransactionTemplate transactionTemplate, Duration outboxRetention,
                        Duration inboxRetention) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.outboxRetention = outboxRetention;
        this.inboxRetention = inboxRetention;
    }

    /** @return rows deleted from outbox and inbox; 0 when another replica holds the lock */
    public int purge() {
        Integer deleted = transactionTemplate.execute(status -> {
            if (!Boolean.TRUE.equals(jdbc.sql(LOCK).query(Boolean.class).single())) {
                return 0;
            }
            return purgeAll(PURGE_OUTBOX, outboxRetention) + purgeAll(PURGE_INBOX, inboxRetention);
        });
        return deleted == null ? 0 : deleted;
    }

    private int purgeAll(String sql, Duration retention) {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.sql(sql).param("seconds", (double) retention.toSeconds()).param("batch", BATCH).update();
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
