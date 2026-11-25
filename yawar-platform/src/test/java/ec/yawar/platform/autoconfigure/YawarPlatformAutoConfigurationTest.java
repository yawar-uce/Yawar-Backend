package ec.yawar.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ec.yawar.platform.envelope.EnvelopeCodec;
import ec.yawar.platform.outbox.Outbox;
import ec.yawar.platform.outbox.OutboxRelay;
import ec.yawar.platform.resilience.DeadlineFilter;
import ec.yawar.platform.resilience.ExternalCallExecutor;
import ec.yawar.platform.tracing.CorrelationIdFilter;
import ec.yawar.platform.tracing.MdcTraceContextProvider;
import ec.yawar.platform.tracing.MicrometerTraceContextProvider;
import ec.yawar.platform.tracing.TraceContextProvider;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.mockito.Mockito.mock;

class YawarPlatformAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    YawarPlatformAutoConfiguration.class));

    @Test
    void withoutDatabaseOrBrokerOnlyCodecTracingAndResilienceAreCreated() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EnvelopeCodec.class)
                    .hasSingleBean(ExternalCallExecutor.class)
                    .doesNotHaveBean(Outbox.class)
                    .doesNotHaveBean(OutboxRelay.class)
                    .doesNotHaveBean(CorrelationIdFilter.class);
            assertThat(ctx.getBean(TraceContextProvider.class)).isExactlyInstanceOf(MdcTraceContextProvider.class);
        });
    }

    @Test
    void usesMicrometerWhenTracerAndPropagatorExist() {
        runner.withBean(Tracer.class, SimpleTracer::new)
                .withBean(Propagator.class, () -> mock(Propagator.class))
                .run(ctx -> assertThat(ctx.getBean(TraceContextProvider.class))
                        .isInstanceOf(MicrometerTraceContextProvider.class));
    }

    @Test
    void servletApplicationGetsCorrelationAndDeadlineFilters() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        YawarPlatformAutoConfiguration.class))
                .withPropertyValues("yawar.platform.resilience.max-deadline=10s")
                .run(ctx -> assertThat(ctx).hasSingleBean(CorrelationIdFilter.class)
                        .hasSingleBean(DeadlineFilter.class));
    }

    @Test
    void propertiesHaveDocumentedDefaults() {
        runner.run(ctx -> {
            YawarPlatformProperties p = ctx.getBean(YawarPlatformProperties.class);
            assertThat(p.consumer().maxAttempts()).isEqualTo(5);
            assertThat(p.outbox().batchSize()).isEqualTo(100);
            assertThat(p.outbox().relayEnabled()).isTrue();
            assertThat(p.resilience().timeout()).hasSeconds(2);
        });
    }
}
