package ec.yawar.platform.inbox;

import ec.yawar.platform.envelope.Envelope;
import ec.yawar.platform.envelope.InvalidEnvelopeException;
import ec.yawar.platform.tracing.Correlation;
import ec.yawar.platform.tracing.TraceContext;
import ec.yawar.platform.tracing.TraceContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessageProperties;

/**
 * Template Method for every Yawar consumer (YAW-DOC-07 §9.4): extract the trace → open a transaction →
 * insert the event id into {@code inbox} → {@link #handle} → commit → ack.
 *
 * <p>A duplicate delivery is acknowledged without calling {@code handle}. When {@code handle} fails the
 * transaction rolls back (the inbox row too) and the message goes to {@code <queue>.retry}, which sends it
 * back after the retry delay. After {@code maxAttempts} deliveries, or at once for an invalid envelope or a
 * {@link NonRetryableMessageException}, it goes to {@code <queue>.dlq}.
 *
 * <p>Subclasses are Spring beans: the platform registers a listener container for each one on
 * {@link #queue()}; declare the queues with {@link ConsumerTopology}.
 */
public abstract class BaseConsumer implements MessageListener {

    public static final String HEADER_ERROR = "x-yawar-error";
    public static final String HEADER_ATTEMPTS = "x-yawar-attempts";

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ConsumerSupport support;

    protected BaseConsumer(ConsumerSupport support) {
        this.support = support;
    }

    /** Queue this consumer reads, named {@code <consumer>.<purpose>}, e.g. {@code traceability.events}. */
    public abstract String queue();

    /**
     * Applies the effect of the message. Runs inside the same transaction as the inbox insert: use the
     * service's repositories, do not call other services. Throw {@link NonRetryableMessageException} for a
     * message that can never succeed.
     */
    protected abstract void handle(Envelope envelope);

    /** Name stored in the inbox; defaults to the queue, override when two consumers share a queue name. */
    public String consumerName() {
        return queue();
    }

    @Override
    public final void onMessage(Message message) {
        Envelope envelope;
        try {
            envelope = support.codec().read(message.getBody());
        } catch (InvalidEnvelopeException e) {
            deadLetter(message, e, 1);
            return;
        }

        TraceContext received = new TraceContext(
                envelope.traceparent(), envelope.correlationId(), envelope.eventId().toString());
        try (TraceContextProvider.Scope ignored =
                     support.traceContextProvider().open(received, "consume " + queue())) {
            process(message, envelope);
        }
    }

    private void process(Message message, Envelope envelope) {
        try {
            Boolean firstTime = support.transactionTemplate().execute(status -> {
                if (!support.inbox().markReceived(envelope.eventId(), consumerName(), envelope.eventType())) {
                    return false;
                }
                handle(envelope);
                return true;
            });
            if (Boolean.FALSE.equals(firstTime)) {
                log.info("Duplicate {} {} ignored by {}", envelope.eventType(), envelope.eventId(), consumerName());
            }
        } catch (NonRetryableMessageException e) {
            deadLetter(message, e, attempt(message));
        } catch (RuntimeException e) {
            // Retry path: the transaction was rolled back; decide between the retry queue and the DLQ.
            long attempt = attempt(message);
            if (attempt >= support.maxAttempts()) {
                deadLetter(message, e, attempt);
                return;
            }
            log.warn("{} {} failed on attempt {}/{}, retrying: {}", envelope.eventType(), envelope.eventId(),
                    attempt, support.maxAttempts(), e.toString());
            throw new AmqpRejectAndDontRequeueException("retry " + envelope.eventId(), e);
        }
    }

    private long attempt(Message message) {
        return DeliveryAttempts.previousFailures(message, queue()) + 1;
    }

    private void deadLetter(Message message, Exception cause, long attempt) {
        MessageProperties props = message.getMessageProperties();
        props.setHeader(HEADER_ERROR, cause.toString());
        props.setHeader(HEADER_ATTEMPTS, attempt);
        support.rabbitTemplate().send("", ConsumerTopology.dlq(queue()), message);
        log.error("Message {} (correlation {}) moved to {} after {} attempt(s): {}", props.getMessageId(),
                props.getHeaders().get(Correlation.AMQP_HEADER), ConsumerTopology.dlq(queue()), attempt,
                cause.toString());
    }
}
