package ec.yawar.platform.outbox;

import ec.yawar.platform.tracing.TraceContext;
import ec.yawar.platform.tracing.TraceContextProvider;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** {@link Outbox} on PostgreSQL with JDBC; joins the caller's transaction (JDBC or JPA). */
public class JdbcOutbox implements Outbox {

    private static final String INSERT = """
            INSERT INTO outbox (id, aggregate_type, aggregate_id, aggregate_version, event_type, exchange,
                                routing_key, schema_version, payload, trace_context)
            VALUES (:id, :aggregateType, :aggregateId, :aggregateVersion, :eventType, :exchange,
                    :routingKey, :schemaVersion, CAST(:payload AS jsonb), CAST(:traceContext AS jsonb))
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TraceContextProvider traceContextProvider;

    public JdbcOutbox(JdbcClient jdbc, ObjectMapper mapper, TraceContextProvider traceContextProvider) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.traceContextProvider = traceContextProvider;
    }

    @Override
    public UUID append(OutboxMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Outbox.append must run inside the business transaction (@Transactional)");
        }
        UUID id = UUID.randomUUID();
        TraceContext trace = traceContextProvider.current();
        if (message.causationId() != null) {
            trace = trace.withCausationId(message.causationId());
        }
        jdbc.sql(INSERT)
                .param("id", id)
                .param("aggregateType", message.aggregateType())
                .param("aggregateId", message.aggregateId())
                .param("aggregateVersion", message.aggregateVersion())
                .param("eventType", message.eventType())
                .param("exchange", message.exchange())
                .param("routingKey", message.routingKey())
                .param("schemaVersion", message.schemaVersion())
                .param("payload", mapper.writeValueAsString(message.payload()))
                .param("traceContext", mapper.writeValueAsString(trace))
                .update();
        return id;
    }
}
