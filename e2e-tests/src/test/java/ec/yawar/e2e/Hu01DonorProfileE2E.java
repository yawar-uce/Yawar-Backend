package ec.yawar.e2e;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * HU-01 through the whole chain: Keycloak token, API Gateway, ms-donors, PostgreSQL. The first test must run first:
 * it is the first call of the donor, the one that creates the profile.
 */
@ExtendWith(E2eEnvironment.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Hu01DonorProfileE2E {

    private static Response me(E2eEnvironment.Stack stack, String token, String ifNoneMatch) {
        var request = given().baseUri(stack.gateway()).auth().oauth2(token);
        if (ifNoneMatch != null) {
            request = request.header("If-None-Match", ifNoneMatch);
        }
        return request.get("/api/v1/donors/me");
    }

    @Test
    @Order(1)
    void e2e_hu01_donorFirstCall_createsProfileThroughGateway(E2eEnvironment.Stack stack) {
        Response res = me(stack, stack.donorToken, null);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.header("ETag")).isNotBlank();
        assertThat(res.header("X-Correlation-Id")).isNotBlank();
        assertThat(res.<String>path("id")).isNotBlank();
        assertThat(res.<Boolean>path("profileComplete")).isFalse();
        assertThat(res.<String>path("consents[0].purpose")).isEqualTo("DATA_PROCESSING");
    }

    @Test
    @Order(2)
    void e2e_hu01_secondCallWithIfNoneMatch_returns304(E2eEnvironment.Stack stack) {
        Response first = me(stack, stack.donorToken, null);
        assertThat(first.statusCode()).isEqualTo(200);
        String etag = first.header("ETag");
        assertThat(etag).isNotBlank();

        Response second = me(stack, stack.donorToken, etag);

        assertThat(second.statusCode()).isEqualTo(304);
        assertThat(second.header("ETag")).isEqualTo(etag);
        assertThat(second.asString()).isEmpty();
        // Same donor, not a second profile: the first response body would carry another id otherwise.
        assertThat(me(stack, stack.donorToken, null).<String>path("id")).isEqualTo(first.<String>path("id"));
    }
}
