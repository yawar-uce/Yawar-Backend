package ec.yawar.platform.outbox;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Purges published outbox rows and old inbox rows (YAW-DOC-07 §9.2: "an extra table … purged periodically"). */
public class Housekeeping {

    private static final String PURGE_OUTBOX =
            "DELETE FROM outbox WHERE published_at < now() - make_interval(secs => :seconds)";
    private static final String PURGE_INBOX =
            "DELETE FROM inbox WHERE received_at < now() - make_interval(secs => :seconds)";

    private final JdbcClient jdbc;
    private final Duration outboxRetention;
    private final Duration inboxRetention;

    public Housekeeping(JdbcClient jdbc, Duration outboxRetention, Duration inboxRetention) {
        this.jdbc = jdbc;
        this.outboxRetention = outboxRetention;
        this.inboxRetention = inboxRetention;
    }

    /** @return rows deleted from outbox and inbox */
    public int purge() {
        int outbox = jdbc.sql(PURGE_OUTBOX).param("seconds", (double) outboxRetention.toSeconds()).update();
        int inbox = jdbc.sql(PURGE_INBOX).param("seconds", (double) inboxRetention.toSeconds()).update();
        return outbox + inbox;
    }
}
