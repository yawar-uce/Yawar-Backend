package ec.yawar.platform.support;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Publisher confirms + returns: a message counts as delivered only when the broker acked it and routed it. */
public final class PublisherConfirms {

    private PublisherConfirms() {
    }

    /** Template for platform publishes: {@code mandatory}, so an unroutable message comes back as returned. */
    public static RabbitTemplate mandatoryTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        return template;
    }

    /** Fails fast when confirms or returns are off: without them a lost message would count as sent. */
    public static void verify(RabbitTemplate template) {
        ConnectionFactory cf = template.getConnectionFactory();
        if (!cf.isPublisherConfirms() || !cf.isPublisherReturns()) {
            throw new IllegalStateException("yawar-platform needs spring.rabbitmq.publisher-confirm-type=correlated "
                    + "and spring.rabbitmq.publisher-returns=true");
        }
    }

    /** Outcome of one publish. */
    public enum Outcome { CONFIRMED, UNROUTABLE, FAILED }

    /** Result of {@link #await}: the outcome and, when not confirmed, a reason without message content. */
    public record Result(Outcome outcome, String reason) {
        public boolean confirmed() {
            return outcome == Outcome.CONFIRMED;
        }
    }

    /** Waits until {@code deadlineNanos} ({@link System#nanoTime()}) for the confirm of one publish. */
    public static Result await(CorrelationData correlation, long deadlineNanos) {
        try {
            long remaining = Math.max(0, deadlineNanos - System.nanoTime());
            CorrelationData.Confirm confirm = correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
            if (!confirm.ack()) {
                return new Result(Outcome.FAILED, "nack: " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                return new Result(Outcome.UNROUTABLE, "unroutable: " + correlation.getReturned().getReplyText());
            }
            return new Result(Outcome.CONFIRMED, null);
        } catch (TimeoutException e) {
            return new Result(Outcome.FAILED, "no publisher confirm within timeout");
        } catch (ExecutionException e) {
            return new Result(Outcome.FAILED, "confirm failed: " + SafeErrors.describe(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.FAILED, "interrupted while waiting for confirm");
        }
    }
}
