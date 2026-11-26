package ec.yawar.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RbacIT extends GatewayRedisITSupport {

    private String tokenWithRoles(String... roles) {
        return token(KEY, UUID.randomUUID().toString(), ISSUER, List.of("yawar-api"), List.of(roles), 300);
    }

    @Test
    void donorToken_onDonorsRoute_returns200AndReachesMsDonors() {
        String token = tokenWithRoles("donor");

        getMe(token).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.donor").isEqualTo(true);

        // The token is forwarded unchanged: ms-donors validates it again.
        DONORS.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/donors/me"))
                .withHeader("Authorization", equalTo("Bearer " + token)));
    }

    @Test
    void logisticsToken_onDonorsRoute_returns403() {
        getMe(tokenWithRoles("logistics")).exchange().expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(403);

        DONORS.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/donors/me")));
    }

    @Test
    void tokenWithoutRoles_onDonorsRoute_returns403() {
        getMe(tokenWithRoles()).exchange().expectStatus().isForbidden();
    }

    @Test
    void unknownApiPath_isDenied() {
        client.get().uri("/api/v1/unknown").header("Authorization", "Bearer " + tokenWithRoles("administrator"))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/unknown").header("X-Forwarded-For", randomIp()).exchange().expectStatus()
                .isUnauthorized();
    }

    @Test
    void fallbackPath_isNotReachableFromOutside() {
        client.get().uri("/fallback/ms-donors").header("Authorization", "Bearer " + tokenWithRoles("donor"))
                .exchange().expectStatus().isForbidden();
        client.get().uri("/fallback/ms-donors").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void probes_arePublicAndDoNotDependOnRedis() {
        client.get().uri("/health/live").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/health/ready").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/info").exchange().expectStatus().isOk();
    }

    @Test
    void actuatorEndpointsNotListed_areDenied() {
        client.get().uri("/health").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
    }
}
