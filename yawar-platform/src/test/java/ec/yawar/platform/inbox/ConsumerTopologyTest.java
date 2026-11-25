package ec.yawar.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.yawar.platform.envelope.Exchanges;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;

class ConsumerTopologyTest {

    @Test
    void declaresQuorumMainRetryAndDlqQueues() {
        Declarables d = ConsumerTopology.events("traceability.events", "donors.#");

        List<Queue> queues = d.getDeclarablesByType(Queue.class);
        assertThat(queues).extracting(Queue::getName)
                .containsExactly("traceability.events", "traceability.events.retry", "traceability.events.dlq");
        assertThat(queues).allSatisfy(q -> {
            assertThat(q.isDurable()).isTrue();
            assertThat(q.getArguments()).containsEntry("x-queue-type", "quorum");
        });

        Map<String, Object> main = queues.get(0).getArguments();
        assertThat(main).containsEntry("x-dead-letter-exchange", "")
                .containsEntry("x-dead-letter-routing-key", "traceability.events.retry");
        assertThat(main).containsEntry("x-dead-letter-strategy", "at-least-once")
                .containsEntry("x-overflow", "reject-publish");
        Map<String, Object> retry = queues.get(1).getArguments();
        assertThat(retry).containsEntry("x-dead-letter-strategy", "at-least-once");
        assertThat(retry).containsEntry("x-message-ttl", 30_000)
                .containsEntry("x-dead-letter-routing-key", "traceability.events");
    }

    @Test
    void bindsEveryRoutingKeyToTheExchange() {
        Declarables d = ConsumerTopology.events("notifications.events", "appointments.#", "campaigns.#");

        assertThat(d.getDeclarablesByType(Exchange.class)).extracting(Exchange::getName, Exchange::getType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Exchanges.EVENTS, "topic"));
        assertThat(d.getDeclarablesByType(Binding.class)).extracting(Binding::getRoutingKey)
                .containsExactly("appointments.#", "campaigns.#");
    }

    @Test
    void commandsUseTheDirectExchangeAndCustomDelay() {
        Declarables d = ConsumerTopology.declare("inventory.commands", ConsumerTopology.commands(),
                Duration.ofMillis(500), "inventory.reserve");

        assertThat(d.getDeclarablesByType(Exchange.class).get(0).getType()).isEqualTo("direct");
        assertThat(d.getDeclarablesByType(Queue.class).get(1).getArguments()).containsEntry("x-message-ttl", 500);
        assertThat(ConsumerTopology.commands("x.commands", "x.do").getDeclarablesByType(Exchange.class).get(0)
                .getName()).isEqualTo(Exchanges.COMMANDS);
    }

    @Test
    void requiresARoutingKey() {
        assertThatThrownBy(() -> ConsumerTopology.events("q")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsOnlyRejectionsFromTheConsumerQueue() {
        MessageProperties props = new MessageProperties();
        props.setHeader("x-death", List.of(
                Map.of("queue", "q", "reason", "rejected", "count", 3L),
                Map.of("queue", "q.retry", "reason", "expired", "count", 3L),
                Map.of("queue", "other", "reason", "rejected", "count", 9L)));
        Message message = new Message(new byte[0], props);

        assertThat(DeliveryAttempts.previousFailures(message, "q")).isEqualTo(3);
        assertThat(DeliveryAttempts.previousFailures(new Message(new byte[0]), "q")).isZero();
    }
}
