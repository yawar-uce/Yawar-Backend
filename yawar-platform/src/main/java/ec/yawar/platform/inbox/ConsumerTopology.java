package ec.yawar.platform.inbox;

import ec.yawar.platform.envelope.Exchanges;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Declares the queues of one consumer as the docs define them (YAW-DOC-07 §8.2):
 * <ul>
 *   <li>{@code <queue>} quorum queue bound to the exchange, dead-lettering to {@code <queue>.retry}</li>
 *   <li>{@code <queue>.retry} quorum queue with a TTL (30 s by default) dead-lettering back to {@code <queue>}</li>
 *   <li>{@code <queue>.dlq} quorum queue for manual review and reprocessing</li>
 * </ul>
 * Expose the result as a {@code @Bean}; Spring AMQP declares it on startup.
 */
public final class ConsumerTopology {

    public static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(30);

    private ConsumerTopology() {
    }

    public static String retry(String queue) {
        return queue + ".retry";
    }

    public static String dlq(String queue) {
        return queue + ".dlq";
    }

    /** Queues bound to {@code yawar.events} with the given routing-key patterns. */
    public static Declarables events(String queue, String... routingKeys) {
        return declare(queue, events(), DEFAULT_RETRY_DELAY, routingKeys);
    }

    /** Queue bound to {@code yawar.commands} with the given command routing keys. */
    public static Declarables commands(String queue, String... routingKeys) {
        return declare(queue, commands(), DEFAULT_RETRY_DELAY, routingKeys);
    }

    public static TopicExchange events() {
        return ExchangeBuilder.topicExchange(Exchanges.EVENTS).durable(true).build();
    }

    public static DirectExchange commands() {
        return ExchangeBuilder.directExchange(Exchanges.COMMANDS).durable(true).build();
    }

    public static Declarables declare(String queue, Exchange exchange, Duration retryDelay, String... routingKeys) {
        if (routingKeys.length == 0) {
            throw new IllegalArgumentException("at least one routing key is required");
        }
        Queue main = QueueBuilder.durable(queue).quorum()
                .deadLetterExchange("")
                .deadLetterRoutingKey(retry(queue))
                .build();
        Queue retry = QueueBuilder.durable(retry(queue)).quorum()
                .ttl(Math.toIntExact(retryDelay.toMillis()))
                .deadLetterExchange("")
                .deadLetterRoutingKey(queue)
                .build();
        Queue dlq = QueueBuilder.durable(dlq(queue)).quorum().build();

        List<Declarable> declarables = new ArrayList<>(List.of(exchange, main, retry, dlq));
        for (String key : routingKeys) {
            Binding binding = BindingBuilder.bind(main).to(exchange).with(key).noargs();
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }
}
