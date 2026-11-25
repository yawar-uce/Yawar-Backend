package ec.yawar.platform.inbox;

import java.util.List;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;

/** Registers one listener container per {@link BaseConsumer} bean (default container factory and prefetch). */
public class ConsumerRegistrar implements RabbitListenerConfigurer {

    private final List<BaseConsumer> consumers;

    public ConsumerRegistrar(List<BaseConsumer> consumers) {
        this.consumers = consumers;
    }

    @Override
    public void configureRabbitListeners(RabbitListenerEndpointRegistrar registrar) {
        for (BaseConsumer consumer : consumers) {
            SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
            endpoint.setId("yawar-" + consumer.consumerName());
            endpoint.setQueueNames(consumer.queue());
            endpoint.setMessageListener(consumer);
            registrar.registerEndpoint(endpoint);
        }
    }
}
