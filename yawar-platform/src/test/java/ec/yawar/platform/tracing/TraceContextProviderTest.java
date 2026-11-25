package ec.yawar.platform.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceContextProviderTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mdcProviderReadsCorrelationFromMdc() {
        MDC.put(Correlation.MDC_CORRELATION_ID, "req-1");
        MDC.put(Correlation.MDC_TRACEPARENT, TRACEPARENT);

        TraceContext current = new MdcTraceContextProvider().current();

        assertThat(current).isEqualTo(new TraceContext(TRACEPARENT, "req-1", null));
    }

    @Test
    void mdcProviderGeneratesCorrelationWhenMissing() {
        TraceContext current = new MdcTraceContextProvider().current();

        assertThat(current.correlationId()).isNotBlank();
        assertThat(current.traceparent()).isNull();
    }

    @Test
    void mdcScopeRestoresPreviousValues() {
        MDC.put(Correlation.MDC_CORRELATION_ID, "outer");
        MdcTraceContextProvider provider = new MdcTraceContextProvider();

        try (TraceContextProvider.Scope ignored =
                     provider.open(new TraceContext(TRACEPARENT, "inner", "evt-1"), "consume q")) {
            assertThat(MDC.get(Correlation.MDC_CORRELATION_ID)).isEqualTo("inner");
            assertThat(MDC.get(Correlation.MDC_TRACEPARENT)).isEqualTo(TRACEPARENT);
        }

        assertThat(MDC.get(Correlation.MDC_CORRELATION_ID)).isEqualTo("outer");
        assertThat(MDC.get(Correlation.MDC_TRACEPARENT)).isNull();
    }

    @Test
    void micrometerProviderBuildsTraceparentFromCurrentSpan() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        io.micrometer.tracing.TraceContext context = mock(io.micrometer.tracing.TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(context.spanId()).thenReturn("00f067aa0ba902b7");
        when(context.sampled()).thenReturn(true);

        TraceContext current = new MicrometerTraceContextProvider(tracer, mock(Propagator.class)).current();

        assertThat(current.traceparent()).isEqualTo(TRACEPARENT);
    }

    @Test
    void micrometerProviderFallsBackToMdcWithoutSpan() {
        Tracer tracer = mock(Tracer.class);
        MDC.put(Correlation.MDC_TRACEPARENT, TRACEPARENT);

        TraceContext current = new MicrometerTraceContextProvider(tracer, mock(Propagator.class)).current();

        assertThat(current.traceparent()).isEqualTo(TRACEPARENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void micrometerScopeStartsConsumerSpanChildOfReceivedTraceparent() {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope inScope = mock(Tracer.SpanInScope.class);
        when(propagator.extract(any(Map.class), any(Propagator.Getter.class))).thenReturn(builder);
        when(builder.name("consume q")).thenReturn(builder);
        when(builder.kind(Span.Kind.CONSUMER)).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(inScope);

        MicrometerTraceContextProvider provider = new MicrometerTraceContextProvider(tracer, propagator);
        try (TraceContextProvider.Scope ignored = provider.open(new TraceContext(TRACEPARENT, "c", null), "consume q")) {
            assertThat(MDC.get(Correlation.MDC_CORRELATION_ID)).isEqualTo("c");
        }

        verify(propagator).extract(any(Map.class), any(Propagator.Getter.class));
        verify(inScope).close();
        verify(span).end();
    }

    @Test
    void micrometerScopeWithoutTraceparentStartsNewSpan() {
        Tracer tracer = mock(Tracer.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.name(any())).thenReturn(builder);
        when(builder.kind(any())).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));

        new MicrometerTraceContextProvider(tracer, mock(Propagator.class))
                .open(new TraceContext(null, "c", null), "consume q").close();

        verify(span).end();
    }

    @Test
    void unsampledTraceparentEndsWith00() {
        io.micrometer.tracing.TraceContext context = mock(io.micrometer.tracing.TraceContext.class);
        when(context.traceId()).thenReturn("a".repeat(32));
        when(context.spanId()).thenReturn("b".repeat(16));
        when(context.sampled()).thenReturn(null);

        assertThat(MicrometerTraceContextProvider.traceparent(context)).endsWith("-00");
    }
}
