package ec.yawar.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ServiceErrorPassThroughIT extends GatewayRedisITSupport {

    @Test
    void msDonors500_passesThroughWithItsOwnProblem() {
        DONORS.stubFor(get(urlPathEqualTo("/api/v1/donors/me")).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":500,\"title\":\"from-service\"}")));

        getMe(donorToken()).exchange().expectStatus().isEqualTo(500)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.title").isEqualTo("from-service");
    }

    @Test
    void msDonors503_getsTheFallbackProblemWithoutRetryAfterWhileTheCircuitIsClosed() {
        DONORS.stubFor(get(urlPathEqualTo("/api/v1/donors/me")).willReturn(aResponse().withStatus(503)));

        getMe(donorToken()).exchange().expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist("Retry-After");
    }
}
