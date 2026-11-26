package ec.yawar.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@DirtiesContext
@TestPropertySource(properties = "yawar.gateway.circuit-breaker.half-open-calls=3")
class SlowServiceIT extends GatewayRedisITSupport {

    @Test
    void slowMsDonors_returns503() {
        DONORS.stubFor(get(urlPathEqualTo("/api/v1/donors/me")).willReturn(ok().withFixedDelay(6000)));
        long start = System.nanoTime();

        client.mutate().responseTimeout(Duration.ofSeconds(12)).build()
                .get().uri("/api/v1/donors/me").header("Authorization", "Bearer " + donorToken())
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);

        // Cut at about 2 s (response timeout and time limiter), not after the 6 s of the service.
        org.assertj.core.api.Assertions.assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isLessThan(Duration.ofMillis(4500));
    }
}
