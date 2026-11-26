package ec.yawar.e2e;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** What the API Gateway does at the edge, seen from outside: authentication, roles, CORS and deny-by-default. */
@ExtendWith(E2eEnvironment.class)
class GatewayEdgeE2E {

    @Test
    void e2e_gateway_withoutToken_returns401ProblemJson(E2eEnvironment.Stack stack) {
        Response res = given().baseUri(stack.gateway()).get("/api/v1/donors/me");

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.contentType()).startsWith("application/problem+json");
        assertThat(res.header("WWW-Authenticate")).startsWith("Bearer");
        assertThat(res.header("X-Correlation-Id")).isNotBlank();
        assertThat(res.<Integer>path("status")).isEqualTo(401);
        assertThat(res.<String>path("title")).isEqualTo("Unauthorized");
    }

    @Test
    void e2e_gateway_userWithoutDonorRole_returns403(E2eEnvironment.Stack stack) {
        Response res = given().baseUri(stack.gateway()).auth().oauth2(stack.noDonorToken).get("/api/v1/donors/me");

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.contentType()).startsWith("application/problem+json");
        assertThat(res.<Integer>path("status")).isEqualTo(403);
    }

    @Test
    void e2e_gateway_corsPreflightFromMobileOrigin_allowed(E2eEnvironment.Stack stack) {
        Response res = given().baseUri(stack.gateway())
                .header("Origin", "capacitor://localhost")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization,if-none-match")
                .options("/api/v1/donors/me");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.header("Access-Control-Allow-Origin")).isEqualTo("capacitor://localhost");
        assertThat(res.header("Access-Control-Allow-Methods")).contains("GET");
        assertThat(res.header("Access-Control-Allow-Headers")).containsIgnoringCase("Authorization")
                .containsIgnoringCase("If-None-Match");
    }

    @Test
    void e2e_gateway_corsPreflightFromUnknownOrigin_rejected(E2eEnvironment.Stack stack) {
        Response res = given().baseUri(stack.gateway())
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")
                .options("/api/v1/donors/me");

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.header("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void e2e_gateway_unknownRoute_isDenied(E2eEnvironment.Stack stack) {
        // Anything not listed is denied: 401 without a token, 403 with a valid donor token. Never 404 or a forward.
        assertThat(given().baseUri(stack.gateway()).get("/api/v1/unknown").statusCode()).isEqualTo(401);
        assertThat(given().baseUri(stack.gateway()).auth().oauth2(stack.donorToken)
                .get("/api/v1/unknown").statusCode()).isEqualTo(403);
        // The internal circuit breaker fallback is not reachable from outside either.
        assertThat(given().baseUri(stack.gateway()).auth().oauth2(stack.donorToken)
                .get("/fallback/ms-donors").statusCode()).isEqualTo(403);
    }
}
