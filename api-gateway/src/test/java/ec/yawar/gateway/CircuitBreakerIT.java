package ec.yawar.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Own context (and closed afterwards): the circuit state must not leak into the other test classes. */
@DirtiesContext
@TestPropertySource(properties = "yawar.gateway.circuit-breaker.wait-in-open-state=30s")
class CircuitBreakerIT extends GatewayRedisITSupport {

    @Test
    void msDonorsFailing_opensCircuit_returns503ProblemJson() {
        DONORS.stubFor(get(urlPathEqualTo("/api/v1/donors/me")).willReturn(aResponse().withStatus(503)));
        String token = donorToken();

        // A failing call answers with the fallback; Retry-After is only promised once the circuit is open.
        getMe(token).exchange().expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist("Retry-After")
                .expectBody().jsonPath("$.status").isEqualTo(503);
        for (int i = 1; i < 20; i++) {
            getMe(token).exchange().expectStatus().isEqualTo(503);
        }
        DONORS.verify(20, getRequestedFor(urlPathEqualTo("/api/v1/donors/me")));

        // 20 failures of 20 calls: the circuit is open and the service is not called any more.
        getMe(token).exchange().expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("Retry-After", "30");
        DONORS.verify(20, getRequestedFor(urlPathEqualTo("/api/v1/donors/me")));
    }
}
