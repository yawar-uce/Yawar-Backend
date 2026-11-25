package ec.yawar.platform.outbox;

import ec.yawar.platform.envelope.Envelope;
import ec.yawar.platform.envelope.EnvelopeCodec;
import ec.yawar.platform.envelope.Exchanges;
import ec.yawar.platform.support.PublisherConfirms;
import ec.yawar.platform.support.SafeErrors;
import ec.yawar.platform.tracing.Correlation;
import ec.yawar.platform.tracing.TraceContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes pending outbox rows to RabbitMQ.
 *
 * <p>Each run locks a batch with {@code FOR UPDATE SKIP LOCKED}, so several replicas never publish the same
 * row at the same time. A row is marked as published only after the broker confirms it (publisher confirms)
 * and, for commands, routed it to a queue ({@code mandatory}): a command nobody can receive is retried, an
 * event without subscribers is logged and considered sent. A failed row is retried with exponential backoff
 * (1 s, 2 s, 4 s … up to 60 s) and never dropped; a row that cannot even be serialized is retried the same way
 * and never blocks the rows behind it. Delivery is at least once: consumers deduplicate with the inbox.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long MAX_BACKOFF_SECONDS = 60;

    private static final String SELECT_PENDING = """
            SELECT id, aggregate_id, aggregate_version, event_type, exchange, routing_key, schema_version,
                   payload::text AS payload, trace_context::text AS trace_context, created_at, attempts
            FROM outbox
            WHERE published_at IS NULL AND next_attempt_at <= now()
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;
    private static final String MARK_PUBLISHED = """
            UPDATE outbox SET published_at = now(), attempts = attempts + 1, last_error = NULL WHERE id IN (:ids)
            """;
    private static final String MARK_FAILED = """
            UPDATE outbox
            SET attempts = attempts + 1, last_error = :error,
                next_attempt_at = now() + make_interval(secs => :delaySeconds)
            WHERE id = :id
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final EnvelopeCodec codec;
    private final String producer;
    private final int batchSize;
    private final Duration confirmTimeout;

    /**
     * @param rabbitTemplate a dedicated template with {@code mandatory=true}
     *                       ({@link PublisherConfirms#mandatoryTemplate})
     */
    public OutboxRelay(JdbcClient jdbc, TransactionTemplate transactionTemplate, RabbitTemplate rabbitTemplate,
                       EnvelopeCodec codec, String producer, int batchSize, Duration confirmTimeout) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.codec = codec;
        this.producer = producer;
        this.batchSize = batchSize;
        this.confirmTimeout = confirmTimeout;
    }

    /** Fails fast when confirms or returns are off: without them a lost message would be marked as sent. */
    public void verifyPublisherConfirms() {
        PublisherConfirms.verify(rabbitTemplate);
    }

    /**
     * Publishes one batch.
     *
     * @return number of rows marked as published
     */
    public int relayOnce() {
        Integer published = transactionTemplate.execute(status -> {
            List<PendingRow> rows = jdbc.sql(SELECT_PENDING)
                    .param("limit", batchSize)
                    .query((rs, n) -> new PendingRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("aggregate_id"),
                            rs.getLong("aggregate_version"),
                            rs.getString("event_type"),
                            rs.getString("exchange"),
                            rs.getString("routing_key"),
                            rs.getInt("schema_version"),
                            rs.getString("payload"),
                            rs.getString("trace_context"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getInt("attempts")))
                    .list();
            return rows.isEmpty() ? 0 : publish(rows);
        });
        return published == null ? 0 : published;
    }

    private int publish(List<PendingRow> rows) {
        List<InFlight> inFlight = new ArrayList<>(rows.size());
        for (PendingRow row : rows) {
            try {
                CorrelationData correlation = new CorrelationData(row.id().toString());
                rabbitTemplate.send(row.exchange(), row.routingKey(), toMessage(row), correlation);
                inFlight.add(new InFlight(row, correlation));
            } catch (RuntimeException e) {
                // Bad row (unreadable payload, invalid envelope) or broker error: retry this row later,
                // keep publishing the others.
                markFailed(row, "send failed: " + SafeErrors.describe(e));
            }
        }

        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        List<UUID> published = new ArrayList<>();
        for (InFlight f : inFlight) {
            PublisherConfirms.Result result = PublisherConfirms.await(f.correlation(), deadline);
            switch (result.outcome()) {
                case CONFIRMED -> published.add(f.row().id());
                case UNROUTABLE -> {
                    if (Exchanges.COMMANDS.equals(f.row().exchange())) {
                        markFailed(f.row(), result.reason());
                    } else {
                        log.warn("Event {} ({}) has no subscriber queue yet; considered published",
                                f.row().id(), f.row().eventType());
                        published.add(f.row().id());
                    }
                }
                case FAILED -> markFailed(f.row(), result.reason());
            }
        }
        if (!published.isEmpty()) {
            jdbc.sql(MARK_PUBLISHED).param("ids", published).update();
        }
        log.debug("Outbox relay published {} of {} messages", published.size(), rows.size());
        return published.size();
    }

    private Message toMessage(PendingRow row) {
        ObjectMapper mapper = codec.mapper();
        TraceContext trace = mapper.readValue(row.traceContext(), TraceContext.class);
        // occurredAt = created_at: the time the business transaction wrote the row.
        Envelope envelope = new Envelope(
                row.id(), row.eventType(), row.aggregateId(), row.aggregateVersion(),
                row.createdAt().toInstant(), row.schemaVersion(), trace.correlationId(), trace.causationId(),
                trace.traceparent(), producer, mapper.readTree(row.payload()));

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding("UTF-8");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(row.id().toString());
        props.setType(row.eventType());
        props.setAppId(producer);
        props.setTimestamp(Date.from(row.createdAt().toInstant()));
        props.setHeader(Correlation.AMQP_HEADER, trace.correlationId());
        if (trace.traceparent() != null) {
            props.setHeader(Correlation.TRACEPARENT, trace.traceparent());
        }
        return new Message(codec.write(envelope), props);
    }

    private void markFailed(PendingRow row, String error) {
        long delay = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(row.attempts(), 6));
        jdbc.sql(MARK_FAILED)
                .param("id", row.id())
                .param("error", error)
                .param("delaySeconds", (double) delay)
                .update();
        log.warn("Outbox message {} ({}) not published, attempt {}, retry in {} s: {}",
                row.id(), row.eventType(), row.attempts() + 1, delay, error);
    }

    private record PendingRow(UUID id, String aggregateId, long aggregateVersion, String eventType,
                              String exchange, String routingKey, int schemaVersion, String payload,
                              String traceContext, OffsetDateTime createdAt, int attempts) {
    }

    private record InFlight(PendingRow row, CorrelationData correlation) {
    }
}
