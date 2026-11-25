package ec.yawar.platform.autoconfigure;

import ec.yawar.platform.envelope.EnvelopeCodec;
import ec.yawar.platform.inbox.BaseConsumer;
import ec.yawar.platform.inbox.ConsumerRegistrar;
import ec.yawar.platform.inbox.ConsumerSupport;
import ec.yawar.platform.inbox.ConsumerTopology;
import ec.yawar.platform.inbox.InboxStore;
import ec.yawar.platform.outbox.Housekeeping;
import ec.yawar.platform.outbox.JdbcOutbox;
import ec.yawar.platform.outbox.Outbox;
import ec.yawar.platform.outbox.OutboxRelay;
import ec.yawar.platform.outbox.PlatformScheduler;
import ec.yawar.platform.resilience.DeadlineFilter;
import ec.yawar.platform.resilience.ExternalCallExecutor;
import ec.yawar.platform.support.PublisherConfirms;
import ec.yawar.platform.tracing.CorrelationIdFilter;
import ec.yawar.platform.tracing.MdcTraceContextProvider;
import ec.yawar.platform.tracing.MicrometerTraceContextProvider;
import ec.yawar.platform.tracing.TraceContextProvider;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the platform into a Yawar service: envelope codec, trace context, outbox + relay, inbox +
 * consumers, HTTP correlation/deadline filters and the external-call executor. Each part is enabled only
 * when its infrastructure (JDBC, RabbitMQ, servlet, Resilience4j) is present.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
        "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration"})
@EnableConfigurationProperties(YawarPlatformProperties.class)
public class YawarPlatformAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    EnvelopeCodec yawarEnvelopeCodec(ObjectMapper mapper) {
        return new EnvelopeCodec(mapper);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class MicrometerTracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TraceContextProvider yawarTraceContextProvider(ObjectProvider<Tracer> tracer,
                                                       ObjectProvider<Propagator> propagator) {
            Tracer t = tracer.getIfAvailable();
            Propagator p = propagator.getIfAvailable();
            return t != null && p != null && t != Tracer.NOOP
                    ? new MicrometerTraceContextProvider(t, p)
                    : new MdcTraceContextProvider();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.tracing.Tracer")
    static class MdcTracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TraceContextProvider yawarTraceContextProvider() {
            return new MdcTraceContextProvider();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean({JdbcClient.class, PlatformTransactionManager.class})
    static class OutboxInboxConfiguration {

        @Bean
        @ConditionalOnMissingBean
        Outbox yawarOutbox(JdbcClient jdbc, ObjectMapper mapper, TraceContextProvider traceContextProvider) {
            return new JdbcOutbox(jdbc, mapper, traceContextProvider);
        }

        @Bean
        @ConditionalOnMissingBean
        InboxStore yawarInboxStore(JdbcClient jdbc) {
            return new InboxStore(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean
        Housekeeping yawarHousekeeping(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                                       YawarPlatformProperties properties) {
            return new Housekeeping(jdbc, new TransactionTemplate(transactionManager),
                    properties.outbox().retention(), properties.inbox().retention());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean({JdbcClient.class, PlatformTransactionManager.class, RabbitTemplate.class})
    static class MessagingConfiguration {

        /** Both exchanges always exist, so publishing never fails because no consumer declared them yet. */
        @Bean
        Declarables yawarExchanges() {
            return new Declarables(ConsumerTopology.events(), ConsumerTopology.commands());
        }

        /** Separate from the service's RabbitTemplate: mandatory publishes, so unroutable messages come back. */
        private static RabbitTemplate platformTemplate(RabbitTemplate serviceTemplate) {
            return PublisherConfirms.mandatoryTemplate(serviceTemplate.getConnectionFactory());
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "yawar.platform.outbox", name = "relay-enabled", matchIfMissing = true)
        OutboxRelay yawarOutboxRelay(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                                     RabbitTemplate rabbitTemplate, EnvelopeCodec codec,
                                     YawarPlatformProperties properties, Environment environment) {
            YawarPlatformProperties.Outbox outbox = properties.outbox();
            return new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), platformTemplate(rabbitTemplate), codec,
                    environment.getProperty("spring.application.name", "unknown-service"),
                    outbox.batchSize(), outbox.confirmTimeout());
        }

        @Bean
        @ConditionalOnMissingBean
        PlatformScheduler yawarPlatformScheduler(ObjectProvider<OutboxRelay> relay, Housekeeping housekeeping,
                                                 YawarPlatformProperties properties) {
            return new PlatformScheduler(relay.getIfAvailable(), housekeeping, properties.outbox().pollInterval(),
                    properties.outbox().purgeInterval());
        }

        @Bean
        @ConditionalOnMissingBean
        ConsumerSupport yawarConsumerSupport(InboxStore inbox, PlatformTransactionManager transactionManager,
                                             EnvelopeCodec codec, TraceContextProvider traceContextProvider,
                                             RabbitTemplate rabbitTemplate, YawarPlatformProperties properties) {
            return new ConsumerSupport(inbox, new TransactionTemplate(transactionManager), codec,
                    traceContextProvider, platformTemplate(rabbitTemplate), properties.consumer().maxAttempts(),
                    properties.outbox().confirmTimeout());
        }

        @Bean
        ConsumerRegistrar yawarConsumerRegistrar(ObjectProvider<BaseConsumer> consumers) {
            return new ConsumerRegistrar(consumers.orderedStream().toList());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CorrelationIdFilter yawarCorrelationIdFilter() {
            return new CorrelationIdFilter();
        }

        @Bean
        @ConditionalOnMissingBean
        DeadlineFilter yawarDeadlineFilter(ObjectProvider<Clock> clock, YawarPlatformProperties properties) {
            return new DeadlineFilter(clock.getIfAvailable(Clock::systemUTC), properties.resilience().maxDeadline());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "io.github.resilience4j.circuitbreaker.CircuitBreaker",
            "io.github.resilience4j.retry.Retry",
            "io.github.resilience4j.bulkhead.Bulkhead"})
    static class ResilienceConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        ExternalCallExecutor yawarExternalCallExecutor(ObjectProvider<CircuitBreakerRegistry> circuitBreakers,
                                                       ObjectProvider<RetryRegistry> retries,
                                                       ObjectProvider<BulkheadRegistry> bulkheads,
                                                       ObjectProvider<Clock> clock,
                                                       TraceContextProvider traceContextProvider,
                                                       YawarPlatformProperties properties) {
            return new ExternalCallExecutor(
                    circuitBreakers.getIfAvailable(CircuitBreakerRegistry::ofDefaults),
                    retries.getIfAvailable(RetryRegistry::ofDefaults),
                    bulkheads.getIfAvailable(BulkheadRegistry::ofDefaults),
                    properties.resilience().timeout(),
                    clock.getIfAvailable(Clock::systemUTC),
                    traceContextProvider);
        }
    }
}
