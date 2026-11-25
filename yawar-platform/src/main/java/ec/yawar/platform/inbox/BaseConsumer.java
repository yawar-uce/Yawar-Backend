package ec.yawar.platform.inbox;

import ec.yawar.platform.envelope.Envelope;
import ec.yawar.platform.envelope.InvalidEnvelopeException;
import ec.yawar.platform.support.PublisherConfirms;
import ec.yawar.platform.support.SafeErrors;
import ec.yawar.platform.tracing.Correlation;
import ec.yawar.platform.tracing.TraceContext;
import ec.yawar.platform.tracing.TraceContextProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/**
 * Template Method for every Yawar consumer (YAW-DOC-07 §9.4): extract the trace → open a transaction →
 * insert the event id into {@code inbox} → {@link #handle} → commit → ack.
 *
 * <p>A duplicate delivery is acknowledged without calling {@code handle}. When {@code handle} fails the
 * transaction rolls back (the inbox row too) and the message goes to {@code <queue>.retry}, which sends it
 * back after the retry delay. After {@code maxAttempts} deliveries, or at once for an invalid envelope or a
 * {@link NonRetryableMessageException}, it goes to {@code <queue>.dlq}; the DLQ publish is confirmed, and if
 * the broker does not confirm it the message takes the retry path instead of being lost.
 *
 * <p>Delivery order is not guaranteed (replicas, retries): projections must store the last
 * {@code aggregateVersion} and ignore older ones. Error descriptions in logs and DLQ headers contain exception
 * classes and SQLStates only, never message text (it may echo personal data).
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
                    attempt, support.maxAttempts(), SafeErrors.describe(e));
            log.debug("Failure detail of {}", envelope.eventId(), e);
            throw new AmqpRejectAndDontRequeueException("retry " + envelope.eventId(), e);
        }
    }

    private long attempt(Message message) {
        return DeliveryAttempts.previousFailures(message, queue()) + 1;
    }

    private void deadLetter(Message message, Exception cause, long attempt) {
        String dlq = ConsumerTopology.dlq(queue());
        String error = SafeErrors.describe(cause);
        MessageProperties props = message.getMessageProperties();
        props.setHeader(HEADER_ERROR, error);
        props.setHeader(HEADER_ATTEMPTS, attempt);

        CorrelationData correlation = new CorrelationData();
        support.rabbitTemplate().send("", dlq, message, correlation);
        PublisherConfirms.Result result = PublisherConfirms.await(correlation,
                System.nanoTime() + support.confirmTimeout().toNanos());
        if (!result.confirmed()) {
            // Do not ack a message that is not safely in the DLQ: send it through the retry queue instead.
            log.error("Could not move message {} to {} ({}); retrying later", props.getMessageId(), dlq,
                    result.reason());
            throw new AmqpRejectAndDontRequeueException("DLQ publish failed for " + props.getMessageId());
        }
        log.error("Message {} (correlation {}) moved to {} after {} attempt(s): {}", props.getMessageId(),
                props.getHeaders().get(Correlation.AMQP_HEADER), dlq, attempt, error);
        log.debug("Failure detail of {}", props.getMessageId(), cause);
    }
}
