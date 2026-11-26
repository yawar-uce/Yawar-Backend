package ec.yawar.gateway.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class CorrelationIdWebFilterTest {

    private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

    @Test
    void clientCorrelationId_isReplacedByAGeneratedUuid() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/donors/me").header(Correlation.HTTP_HEADER, "chosen-by-client").build());
        AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();

        filter.filter(exchange, ex -> {
            forwarded.set(ex.getRequest());
            return ex.getResponse().setComplete();
        }).block();

        String id = forwarded.get().getHeaders().getFirst(Correlation.HTTP_HEADER);
        assertThat(id).isNotEqualTo("chosen-by-client").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(Correlation.isValid(id)).isTrue();
        assertThat(exchange.getResponse().getHeaders().get(Correlation.HTTP_HEADER)).containsExactly(id);
    }

    @Test
    void responseHeader_overridesAValueSetByTheService() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());

        filter.filter(exchange, ex -> {
            ex.getResponse().getHeaders().add(Correlation.HTTP_HEADER, "service-own");
            return ex.getResponse().setComplete();
        }).block();

        assertThat(exchange.getResponse().getHeaders().get(Correlation.HTTP_HEADER)).hasSize(1)
                .doesNotContain("service-own");
    }

    @Test
    void id_isInTheReactorContext() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());
        AtomicReference<Object> seen = new AtomicReference<>();

        filter.filter(exchange, (ServerWebExchange ex) -> Mono.deferContextual(ctx -> {
            seen.set(ctx.get(Correlation.MDC_CORRELATION_ID));
            return ex.getResponse().setComplete();
        })).block();

        assertThat(seen.get()).isEqualTo(exchange.getResponse().getHeaders().getFirst(Correlation.HTTP_HEADER));
    }

    @Test
    void validation_followsTheSharedPattern() {
        assertThat(Correlation.isValid("abc-123_x.y:z")).isTrue();
        assertThat(Correlation.isValid("bad value\n")).isFalse();
        assertThat(Correlation.isValid("")).isFalse();
        assertThat(Correlation.isValid(null)).isFalse();
        assertThat(Correlation.isValid("a".repeat(129))).isFalse();
    }

    @Test
    void mdcAccessor_setsReadsAndClearsTheMdc() {
        var accessor = new MdcCorrelationAccessor();

        accessor.setValue("abc");
        assertThat(accessor.getValue()).isEqualTo("abc");
        assertThat(accessor.key()).isEqualTo("correlationId");
        accessor.setValue();
        assertThat(MDC.get("correlationId")).isNull();
    }
}
