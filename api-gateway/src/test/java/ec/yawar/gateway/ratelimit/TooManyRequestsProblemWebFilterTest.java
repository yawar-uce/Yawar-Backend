package ec.yawar.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class TooManyRequestsProblemWebFilterTest {

    private final TooManyRequestsProblemWebFilter filter = new TooManyRequestsProblemWebFilter();

    @Test
    void emptyTooManyRequestsResponse_becomesAProblemKeepingItsHeaders() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/donors/me").build());

        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            ex.getResponse().getHeaders().set("X-RateLimit-Remaining", "0");
            return ex.getResponse().setComplete();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"status\":429");
    }

    @Test
    void otherResponses_areLeftUntouched() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());

        filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return ex.getResponse().setComplete();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isNull();
    }
}
