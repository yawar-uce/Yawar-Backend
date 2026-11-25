package ec.yawar.platform.inbox;

import ec.yawar.platform.envelope.EnvelopeCodec;
import ec.yawar.platform.tracing.TraceContextProvider;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collaborators every {@link BaseConsumer} needs; one bean created by the platform auto-configuration and
 * passed to the consumer constructor.
 *
 * @param maxAttempts deliveries before a message goes to the DLQ (5 by default, YAW-DOC-07 §9.4)
 */
public record ConsumerSupport(
        InboxStore inbox,
        TransactionTemplate transactionTemplate,
        EnvelopeCodec codec,
        TraceContextProvider traceContextProvider,
        RabbitTemplate rabbitTemplate,
        int maxAttempts) {

    public ConsumerSupport {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }
}
