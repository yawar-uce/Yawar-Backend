package ec.yawar.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ec.yawar.platform.envelope.Envelope;
import ec.yawar.platform.envelope.EnvelopeCodec;
import ec.yawar.platform.envelope.Exchanges;
import ec.yawar.platform.inbox.BaseConsumer;
import ec.yawar.platform.outbox.Housekeeping;
import ec.yawar.platform.outbox.Outbox;
import ec.yawar.platform.outbox.OutboxMessage;
import ec.yawar.platform.outbox.OutboxRelay;
import ec.yawar.platform.outbox.PlatformScheduler;
import ec.yawar.platform.tracing.Correlation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;

/** Outbox, relay, inbox and BaseConsumer against real PostgreSQL 16 and RabbitMQ 4.2 (Testcontainers). */
@SpringBootTest(classes = PlatformTestApplication.class)
@Testcontainers
class PlatformIT {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.9-management-alpine");

    @Autowired Outbox outbox;
    @Autowired OutboxRelay relay;
    @Autowired Housekeeping housekeeping;
    @Autowired PlatformScheduler scheduler;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired EnvelopeCodec codec;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTestApplication.ItConsumer consumer;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** Server-named queue bound to yawar.events with the given pattern, to capture what the relay sends. */
    private String captureQueue(String routingKey) {
        Queue queue = new AnonymousQueue();
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(Exchanges.EVENTS)).with(routingKey));
        return queue.getName();
    }

    private long count(String sql, Object id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    // --- Outbox + relay ---------------------------------------------------------------------------

    @Test
    void committedEventIsPublishedWithEnvelopeAndTraceHeaders() {
        String queue = captureQueue("it.donor.registered");
        MDC.put(Correlation.MDC_CORRELATION_ID, "req-8812");
        MDC.put(Correlation.MDC_TRACEPARENT, TRACEPARENT);

        UUID id = tx.execute(s -> outbox.append(
                OutboxMessage.event("it.donor.registered", "donor", "D-42", 1, Map.of("donorId", "D-42"))
                        .withCausationId("cmd-1")));

        Message message = await().atMost(Duration.ofSeconds(10))
                .until(() -> rabbit.receive(queue), m -> m != null);
        Envelope envelope = codec.read(message.getBody());
        assertThat(envelope.eventId()).isEqualTo(id);
        assertThat(envelope.eventType()).isEqualTo("it.donor.registered");
        assertThat(envelope.aggregateId()).isEqualTo("D-42");
        assertThat(envelope.correlationId()).isEqualTo("req-8812");
        assertThat(envelope.causationId()).isEqualTo("cmd-1");
        assertThat(envelope.traceparent()).isEqualTo(TRACEPARENT);
        assertThat(envelope.producer()).isEqualTo("platform-it");
        assertThat(envelope.payload().get("donorId").asString()).isEqualTo("D-42");

        MessageProperties props = message.getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(id.toString());
        assertThat(props.<String>getHeader(Correlation.TRACEPARENT)).isEqualTo(TRACEPARENT);
        assertThat(props.<String>getHeader(Correlation.AMQP_HEADER)).isEqualTo("req-8812");
        await().atMost(Duration.ofSeconds(5)).until(() ->
                count("SELECT count(*) FROM outbox WHERE id = :id AND published_at IS NOT NULL", id) == 1);
    }

    @Test
    void rolledBackTransactionLeavesNothingToPublish() {
        String aggregate = "rollback-" + UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            outbox.append(OutboxMessage.event("it.rollback", "donor", aggregate, 1, Map.of()));
            throw new IllegalStateException("business rule failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("SELECT count(*) FROM outbox WHERE aggregate_id = :id", aggregate)).isZero();
    }

    @Test
    void appendOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> outbox.append(OutboxMessage.event("it.x", "donor", "D-1", 1, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }

    @Test
    void concurrentRelaysPublishEveryRowExactlyOnce() throws Exception {
        String queue = captureQueue("it.burst");
        int total = 300;
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < total; i++) {
                outbox.append(OutboxMessage.event("it.burst", "unit", "U-" + i, 1, Map.of("n", i)));
            }
        });

        // Four relays race with the scheduled one: SKIP LOCKED must hand each row to one of them only.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> runs = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            runs.add(pool.submit(() -> {
                while (relay.relayOnce() > 0) {
                    Thread.onSpinWait();
                }
            }));
        }
        for (Future<?> run : runs) {
            run.get();
        }
        pool.shutdown();

        Set<UUID> ids = new HashSet<>();
        List<UUID> all = new ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            Message m;
            while ((m = rabbit.receive(queue)) != null) {
                UUID id = codec.read(m.getBody()).eventId();
                all.add(id);
                ids.add(id);
            }
            return all.size() >= total;
        });
        assertThat(all).hasSize(total);
        assertThat(ids).hasSize(total);
    }

    @Test
    void publishFailureKeepsTheRowAndSchedulesARetry() {
        String aggregate = "missing-" + UUID.randomUUID();
        UUID id = tx.execute(s -> outbox.append(new OutboxMessage("it.missing.exchange", "x", "it.x", "donor",
                aggregate, 1, 1, Map.of(), null)));

        await().atMost(Duration.ofSeconds(10)).until(() ->
                count("SELECT attempts FROM outbox WHERE id = :id", id) >= 1);

        Map<String, Object> row = jdbc.sql("""
                SELECT published_at, last_error, next_attempt_at > now() AS later FROM outbox WHERE id = :id
                """).param("id", id).query().singleRow();
        assertThat(row.get("published_at")).isNull();
        assertThat((String) row.get("last_error")).isNotBlank();
        assertThat((Boolean) row.get("later")).isTrue();
        jdbc.sql("DELETE FROM outbox WHERE id = :id").param("id", id).update();
    }

    @Test
    void commandNobodyCanReceiveIsRetriedNotLost() {
        UUID id = tx.execute(s -> outbox.append(
                OutboxMessage.command("nobody.listens", "request", "R-" + UUID.randomUUID(), 1, Map.of())));

        await().atMost(Duration.ofSeconds(10)).until(() ->
                count("SELECT attempts FROM outbox WHERE id = :id", id) >= 1);

        Map<String, Object> row = jdbc.sql("SELECT published_at, last_error FROM outbox WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(row.get("published_at")).isNull();
        assertThat((String) row.get("last_error")).startsWith("unroutable");
        jdbc.sql("DELETE FROM outbox WHERE id = :id").param("id", id).update();
    }

    @Test
    void eventWithoutSubscribersIsConsideredPublished() {
        UUID id = tx.execute(s -> outbox.append(OutboxMessage.event("it.nobody.subscribes", "donor", "D-9", 1, Map.of())));

        await().atMost(Duration.ofSeconds(10)).until(() ->
                count("SELECT count(*) FROM outbox WHERE id = :id AND published_at IS NOT NULL", id) == 1);
    }

    @Test
    void eventPublishedWhileHandlingAMessageCarriesItsCausationAndCorrelation() {
        String queue = captureQueue("it.caused");
        Envelope received = envelope("{\"emit\":true}");

        send(received);

        Message message = await().atMost(Duration.ofSeconds(10))
                .until(() -> rabbit.receive(queue), m -> m != null);
        Envelope caused = codec.read(message.getBody());
        assertThat(caused.causationId()).isEqualTo(received.eventId().toString());
        assertThat(caused.correlationId()).isEqualTo(received.correlationId());
        assertThat(caused.traceparent()).isEqualTo(TRACEPARENT);
    }

    @Test
    void housekeepingPurgesOldPublishedRowsAndOldInboxRows() {
        UUID id = tx.execute(s -> outbox.append(OutboxMessage.event("it.old", "donor", "D-old", 1, Map.of())));
        jdbc.sql("UPDATE outbox SET published_at = now() - interval '8 days' WHERE id = :id").param("id", id).update();
        UUID inboxId = UUID.randomUUID();
        jdbc.sql("INSERT INTO inbox (event_id, consumer, event_type, received_at) "
                + "VALUES (:id, 'old', 'x', now() - interval '31 days')").param("id", inboxId).update();

        assertThat(housekeeping.purge()).isGreaterThanOrEqualTo(2);

        assertThat(count("SELECT count(*) FROM outbox WHERE id = :id", id)).isZero();
        assertThat(count("SELECT count(*) FROM inbox WHERE event_id = :id", inboxId)).isZero();
    }

    @Test
    void schedulerIsRunning() {
        assertThat(scheduler.isRunning()).isTrue();
    }

    // --- Inbox + BaseConsumer ---------------------------------------------------------------------

    private Envelope envelope(String payloadJson) {
        return new Envelope(UUID.randomUUID(), "it.consumer.test", "A-1", 1, Instant.now(), 1, "corr-" + UUID.randomUUID(),
                null, TRACEPARENT, "it", mapper.readTree(payloadJson));
    }

    private void send(Envelope envelope) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(envelope.eventId().toString());
        rabbit.send(Exchanges.EVENTS, "it.consumer.test", new Message(codec.write(envelope), props));
    }

    private long effects(UUID eventId) {
        return count("SELECT count(*) FROM it_effect WHERE event_id = :id", eventId);
    }

    @Test
    void sameEventDeliveredThreeTimesHasOneEffect() {
        Envelope envelope = envelope("{}");

        send(envelope);
        send(envelope);
        send(envelope);

        await().atMost(Duration.ofSeconds(10)).until(() -> effects(envelope.eventId()) == 1);
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> true);
        assertThat(effects(envelope.eventId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inbox WHERE event_id = :id", envelope.eventId())).isEqualTo(1);
        assertThat(consumer.correlationSeen.get(envelope.eventId())).isEqualTo(envelope.correlationId());
    }

    @Test
    void transientFailureIsRetriedThroughTheRetryQueueThenSucceeds() {
        Envelope envelope = envelope("{\"failTimes\":2}");

        send(envelope);

        await().atMost(Duration.ofSeconds(15)).until(() -> effects(envelope.eventId()) == 1);
        assertThat(consumer.deliveries.get(envelope.eventId())).hasValue(3);
    }

    @Test
    void messageFailingFiveTimesEndsInTheDlq() {
        Envelope envelope = envelope("{\"failTimes\":-1}");

        send(envelope);

        Message dead = await().atMost(Duration.ofSeconds(20))
                .until(() -> rabbit.receive(PlatformTestApplication.CONSUMER_QUEUE + ".dlq"), m -> m != null);
        assertThat(codec.read(dead.getBody()).eventId()).isEqualTo(envelope.eventId());
        assertThat(((Number) dead.getMessageProperties().getHeader(BaseConsumer.HEADER_ATTEMPTS)).longValue())
                .isEqualTo(5);
        assertThat(consumer.deliveries.get(envelope.eventId())).hasValue(5);
        assertThat(effects(envelope.eventId())).isZero();
        assertThat(count("SELECT count(*) FROM inbox WHERE event_id = :id", envelope.eventId())).isZero();
    }

    @Test
    void poisonMessagesGoToTheDlqWithoutRetries() {
        Envelope poison = envelope("{\"poison\":true}");
        send(poison);
        rabbit.send(Exchanges.EVENTS, "it.consumer.test",
                new Message("not an envelope".getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        List<Message> dead = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            Message m = rabbit.receive(PlatformTestApplication.CONSUMER_QUEUE + ".dlq");
            if (m != null) {
                dead.add(m);
            }
            return dead.size() >= 2;
        });
        assertThat(consumer.deliveries.get(poison.eventId())).hasValue(1);
        assertThat(dead).extracting(m -> m.getMessageProperties().<String>getHeader(BaseConsumer.HEADER_ERROR))
                .anySatisfy(e -> assertThat(e).contains("NonRetryableMessageException"))
                .anySatisfy(e -> assertThat(e).contains("InvalidEnvelopeException"));
    }
}
