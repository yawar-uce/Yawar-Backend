package ec.yawar.platform.it;

import ec.yawar.platform.envelope.Envelope;
import ec.yawar.platform.inbox.BaseConsumer;
import ec.yawar.platform.inbox.ConsumerSupport;
import ec.yawar.platform.inbox.ConsumerTopology;
import ec.yawar.platform.outbox.Outbox;
import ec.yawar.platform.outbox.OutboxMessage;
import ec.yawar.platform.tracing.Correlation;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.MDC;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Minimal service used by the integration tests: one consumer writing an effect row. */
@SpringBootApplication
public class PlatformTestApplication {

    static final String CONSUMER_QUEUE = "it.consumer";

    @Bean
    Declarables itConsumerTopology() {
        return ConsumerTopology.declare(CONSUMER_QUEUE, ConsumerTopology.events(), Duration.ofMillis(300),
                "it.consumer.#");
    }

    /**
     * Payload {@code {"failTimes": n}} fails the first n deliveries; {@code -1} always fails;
     * {@code {"poison": true}} throws a non-retryable error; {@code {"emit": true}} publishes {@code it.caused}.
     */
    @Component
    static class ItConsumer extends BaseConsumer {

        final Map<UUID, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        final Map<UUID, String> correlationSeen = new ConcurrentHashMap<>();
        private final JdbcClient jdbc;
        private final Outbox outbox;

        ItConsumer(ConsumerSupport support, JdbcClient jdbc, Outbox outbox) {
            super(support);
            this.jdbc = jdbc;
            this.outbox = outbox;
        }

        @Override
        public String queue() {
            return CONSUMER_QUEUE;
        }

        @Override
        protected void handle(Envelope envelope) {
            int delivery = deliveries.computeIfAbsent(envelope.eventId(), k -> new AtomicInteger()).incrementAndGet();
            correlationSeen.put(envelope.eventId(), MDC.get(Correlation.MDC_CORRELATION_ID));
            jdbc.sql("INSERT INTO it_effect (event_id) VALUES (:id)").param("id", envelope.eventId()).update();
            if (envelope.payload().path("emit").asBoolean(false)) {
                outbox.append(OutboxMessage.event("it.caused", "x", envelope.aggregateId(), 1, Map.of()));
            }
            if (envelope.payload().path("poison").asBoolean(false)) {
                throw new ec.yawar.platform.inbox.NonRetryableMessageException("poison");
            }
            int failTimes = envelope.payload().path("failTimes").asInt(0);
            if (failTimes < 0 || delivery <= failTimes) {
                throw new IllegalStateException("simulated failure " + delivery);
            }
        }
    }
}
