package ec.yawar.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class JwtValidationIT extends GatewayRedisITSupport {

    private WebTestClient.ResponseSpec call(String token) {
        return getMe(token).exchange();
    }

    private void assertUnauthorized(WebTestClient.ResponseSpec response, String challenge) {
        response.expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, challenge)
                .expectHeader().exists("X-Correlation-Id")
                .expectBody().jsonPath("$.status").isEqualTo(401);
        DONORS.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/donors/me")));
    }

    @Test
    void validToken_reachesTheService() {
        call(donorToken()).expectStatus().isOk();
    }

    @Test
    void expiredToken_returns401() {
        String expired = token(KEY, "u", ISSUER, List.of("yawar-api"), List.of("donor"), -120);

        assertUnauthorized(call(expired), "Bearer error=\"invalid_token\"");
    }

    @Test
    void tamperedSignature_returns401() {
        // Signed with a key Keycloak never published, claiming the published key id.
        String forged = token(ROGUE_KEY, "u", ISSUER, List.of("yawar-api"), List.of("donor"), 300);

        assertUnauthorized(call(forged), "Bearer error=\"invalid_token\"");
    }

    @Test
    void tamperedPayload_returns401() {
        String[] parts = token(KEY, "u", ISSUER, List.of("yawar-api"), List.of("auditor"), 300).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1])).replace("auditor", "donor");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes())
                + "." + parts[2];

        assertUnauthorized(call(tampered), "Bearer error=\"invalid_token\"");
    }

    @Test
    void wrongIssuer_returns401() {
        String token = token(KEY, "u", "http://evil.test/realms/yawar-it", List.of("yawar-api"), List.of("donor"), 300);

        assertUnauthorized(call(token), "Bearer error=\"invalid_token\"");
    }

    @Test
    void wrongAudience_returns401() {
        String token = token(KEY, "u", ISSUER, List.of("account"), List.of("donor"), 300);

        assertUnauthorized(call(token), "Bearer error=\"invalid_token\"");
    }

    @Test
    void missingToken_returns401() {
        assertUnauthorized(call(null), "Bearer");
    }

    @Test
    void malformedToken_returns401() {
        assertUnauthorized(call("not-a-jwt"), "Bearer error=\"invalid_token\"");
    }

    @Test
    void cachedJwks_stillValidatesWhenKeycloakIsDown() {
        call(donorToken()).expectStatus().isOk();

        KEYCLOAK.resetAll();
        KEYCLOAK.stubFor(get(urlPathEqualTo("/jwks")).willReturn(aResponse().withStatus(503)));

        call(donorToken()).expectStatus().isOk();
    }

    @Test
    void tokensWithUnknownKeyIds_doNotMakeTheGatewayHammerKeycloak() {
        call(donorToken()).expectStatus().isOk();
        KEYCLOAK.resetRequests();

        for (int i = 0; i < 100; i++) {
            String forged = token(randomKidKey(), "u", ISSUER, List.of("yawar-api"), List.of("donor"), 300);
            call(forged).expectStatus().isUnauthorized();
        }

        // At most one refresh per 30 s however many unknown key ids arrive.
        assertThat(KEYCLOAK.countRequestsMatching(getRequestedFor(urlPathEqualTo("/jwks")).build()).getCount())
                .isLessThanOrEqualTo(1);
    }

    private static RSAKey randomKidKey() {
        return new RSAKey.Builder(ROGUE_KEY).keyID(java.util.UUID.randomUUID().toString()).build();
    }
}
