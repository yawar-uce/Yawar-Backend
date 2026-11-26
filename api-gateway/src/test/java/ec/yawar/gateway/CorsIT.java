package ec.yawar.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

class CorsIT extends GatewayRedisITSupport {

    private EntityExchangeResult<byte[]> preflight(String origin) {
        return client.options().uri("/api/v1/donors/me")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,if-match,idempotency-key")
                .exchange().expectBody().returnResult();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://localhost:3000", "http://localhost", "https://localhost",
            "capacitor://localhost"})
    void preflight_allowedForDevOrigin(String origin) {
        EntityExchangeResult<byte[]> result = preflight(origin);

        assertThat(result.getStatus().value()).isEqualTo(200);
        HttpHeaders headers = result.getResponseHeaders();
        assertThat(headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(origin);
        assertThat(headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("PUT", "PATCH", "DELETE");
        assertThat(headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)).containsIgnoringCase("Idempotency-Key")
                .containsIgnoringCase("If-Match");
        assertThat(headers.getFirst(HttpHeaders.ACCESS_CONTROL_MAX_AGE)).isEqualTo("3600");
        assertThat(headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    @Test
    void preflight_rejectedForOtherOrigin() {
        EntityExchangeResult<byte[]> result = preflight("https://evil.example");

        assertThat(result.getStatus().value()).isEqualTo(403);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void preflight_rejectedForUnlistedHeader() {
        EntityExchangeResult<byte[]> result = client.options().uri("/api/v1/donors/me")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-evil")
                .exchange().expectBody().returnResult();

        assertThat(result.getStatus().value()).isEqualTo(403);
    }

    @Test
    void actualRequest_exposesTheDocumentedHeaders() {
        EntityExchangeResult<byte[]> result = getMe(donorToken()).header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .exchange().expectStatus().isOk().expectBody().returnResult();

        HttpHeaders headers = result.getResponseHeaders();
        assertThat(headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly("http://localhost:5173");
        assertThat(headers.getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)).contains("ETag", "Retry-After",
                "X-RateLimit-Remaining", "X-Correlation-Id", "Location");
    }

    @Test
    void errorResponse_alsoCarriesCorsHeaders() {
        EntityExchangeResult<byte[]> result = getMe(null).header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .exchange().expectStatus().isUnauthorized().expectBody().returnResult();

        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://localhost:5173");
    }

    @Test
    void preflight_allowsTraceContextHeaders() {
        EntityExchangeResult<byte[]> result = client.options().uri("/api/v1/donors/me")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "traceparent,tracestate")
                .exchange().expectBody().returnResult();

        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .containsIgnoringCase("traceparent").containsIgnoringCase("tracestate");
    }

    @Test
    void serviceCorsHeader_isNotDuplicated() {
        DONORS.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .get(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/api/v1/donors/me"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("{}")
                        .withHeader("Access-Control-Allow-Origin", "*")));

        EntityExchangeResult<byte[]> result = getMe(donorToken()).header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .exchange().expectStatus().isOk().expectBody().returnResult();

        assertThat(result.getResponseHeaders().get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .containsExactly("http://localhost:5173");
    }
}
