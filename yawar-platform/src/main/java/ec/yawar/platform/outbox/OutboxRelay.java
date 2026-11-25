package ec.yawar.platform.outbox;

import ec.yawar.platform.envelope.Envelope;
import ec.yawar.platform.envelope.EnvelopeCodec;
import ec.yawar.platform.tracing.Correlation;
import ec.yawar.platform.tracing.TraceContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
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
 * row at the same time. A row is marked as published only after the broker confirms it (publisher confirms,
 * {@code spring.rabbitmq.publisher-confirm-type=correlated}). A failed row is retried with exponential
 * backoff (1 s, 2 s, 4 s … up to 60 s) and is never dropped. Delivery is at least once: consumers deduplicate
 * with the inbox.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long MAX_BACKOFF_SECONDS = 60;
    private static final int MAX_ERROR_LENGTH = 1000;

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

    /** Fails fast when publisher confirms are off: without them a lost message would be marked as sent. */
    public void verifyPublisherConfirms() {
        if (!rabbitTemplate.getConnectionFactory().isPublisherConfirms()) {
            throw new IllegalStateException(
                    "yawar-platform outbox relay needs spring.rabbitmq.publisher-confirm-type=correlated");
        }
    }

    /**
     * Publishes one batch.
     *
     * @return number of rows confirmed by the broker
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
            CorrelationData correlation = new CorrelationData(row.id().toString());
            try {
                rabbitTemplate.send(row.exchange(), row.routingKey(), toMessage(row), correlation);
                inFlight.add(new InFlight(row, correlation));
            } catch (AmqpException e) {
                markFailed(row, "send failed: " + e.getMessage());
            }
        }

        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        List<UUID> confirmed = new ArrayList<>();
        for (InFlight f : inFlight) {
            String failure = awaitConfirm(f.correlation(), deadline);
            if (failure == null) {
                confirmed.add(f.row().id());
            } else {
                markFailed(f.row(), failure);
            }
        }
        if (!confirmed.isEmpty()) {
            jdbc.sql(MARK_PUBLISHED).param("ids", confirmed).update();
        }
        if (log.isDebugEnabled()) {
            log.debug("Outbox relay published {} of {} messages", confirmed.size(), rows.size());
        }
        return confirmed.size();
    }

    /** Returns {@code null} when the broker acked the message, otherwise the reason. */
    private static String awaitConfirm(CorrelationData correlation, long deadlineNanos) {
        try {
            long remaining = Math.max(0, deadlineNanos - System.nanoTime());
            CorrelationData.Confirm confirm = correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
            return confirm.ack() ? null : "nack: " + confirm.reason();
        } catch (TimeoutException e) {
            return "no publisher confirm within timeout";
        } catch (ExecutionException e) {
            return "confirm failed: " + e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted while waiting for confirm";
        }
    }

    private Message toMessage(PendingRow row) {
        ObjectMapper mapper = codec.mapper();
        TraceContext trace = mapper.readValue(row.traceContext(), TraceContext.class);
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
        String trimmed = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        jdbc.sql(MARK_FAILED)
                .param("id", row.id())
                .param("error", trimmed)
                .param("delaySeconds", (double) delay)
                .update();
        log.warn("Outbox message {} ({}) not published, attempt {}, retry in {} s: {}",
                row.id(), row.eventType(), row.attempts() + 1, delay, trimmed);
    }

    private record PendingRow(UUID id, String aggregateId, long aggregateVersion, String eventType,
                              String exchange, String routingKey, int schemaVersion, String payload,
                              String traceContext, OffsetDateTime createdAt, int attempts) {
    }

    private record InFlight(PendingRow row, CorrelationData correlation) {
    }
}
