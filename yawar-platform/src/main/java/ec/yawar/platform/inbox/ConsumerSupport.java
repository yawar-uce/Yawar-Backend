package ec.yawar.platform.inbox;

import ec.yawar.platform.envelope.EnvelopeCodec;
import ec.yawar.platform.tracing.TraceContextProvider;
import java.time.Duration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collaborators every {@link BaseConsumer} needs; one bean created by the platform auto-configuration and
 * passed to the consumer constructor.
 *
 * @param rabbitTemplate dedicated {@code mandatory} template used for confirmed DLQ publishes
 * @param maxAttempts    deliveries before a message goes to the DLQ (5 by default, YAW-DOC-07 §9.4)
 * @param confirmTimeout max wait for the broker to confirm a DLQ publish
 */
public record ConsumerSupport(
        InboxStore inbox,
        TransactionTemplate transactionTemplate,
        EnvelopeCodec codec,
        TraceContextProvider traceContextProvider,
        RabbitTemplate rabbitTemplate,
        int maxAttempts,
        Duration confirmTimeout) {

    public ConsumerSupport {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }
}
