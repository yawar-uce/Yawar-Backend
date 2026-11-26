package ec.yawar.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

class TracingIT extends GatewayRedisITSupport {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void downstream_getsGeneratedCorrelationIdAndTraceparent() {
        EntityExchangeResult<byte[]> result = getMe(donorToken()).header("X-Correlation-Id", "chosen-by-client")
                .exchange().expectStatus().isOk().expectBody().returnResult();

        LoggedRequest forwarded = DONORS.findAll(getRequestedFor(urlPathEqualTo("/api/v1/donors/me"))).getFirst();
        String correlationId = forwarded.getHeader("X-Correlation-Id");
        assertThat(correlationId).matches(UUID_PATTERN).isNotEqualTo("chosen-by-client");
        assertThat(forwarded.getHeader("traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        assertThat(result.getResponseHeaders().get("X-Correlation-Id")).containsExactly(correlationId);
    }

    @Test
    void rejectedRequest_alsoReturnsACorrelationId() {
        EntityExchangeResult<byte[]> result = getMe(null).exchange().expectStatus().isUnauthorized()
                .expectBody().returnResult();

        assertThat(result.getResponseHeaders().getFirst("X-Correlation-Id")).matches(UUID_PATTERN);
        assertThat(new String(result.getResponseBody())).contains(result.getResponseHeaders().getFirst("X-Correlation-Id"));
    }
}
