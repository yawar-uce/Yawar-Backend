package ec.yawar.donors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class DonorsMeIT extends AbstractDonorsIT {

    @Test
    void ifNoneMatchWithTheCurrentEtagReturns304() throws Exception {
        UUID sub = UUID.randomUUID();
        mvc.perform(get("/api/v1/donors/me").with(donorToken(sub))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/donors/me").with(donorToken(sub)).header("If-None-Match", "\"0\""))
                .andExpect(status().isNotModified());
        mvc.perform(get("/api/v1/donors/me").with(donorToken(sub)).header("If-None-Match", "\"7\""))
                .andExpect(status().isOk());
    }

    @Test
    void secondCallReturnsTheSameDonorAndPublishesNothingMore() throws Exception {
        UUID sub = UUID.randomUUID();
        String first = mvc.perform(get("/api/v1/donors/me").with(donorToken(sub))).andReturn().getResponse()
                .getContentAsString();
        // Existing donors keep working even when a later token carries no consent claim.
        String second = mvc.perform(get("/api/v1/donors/me").with(donorTokenWithoutConsent(sub)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(first, "$.id");
        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(id);
        assertThat(count("outbox", "aggregate_id = ?", id)).isEqualTo(1);
        assertThat(count("consent", "donor_id = ?", UUID.fromString(id))).isEqualTo(1);
    }

    @Test
    void googleSignupIsReportedAsGoogleInTheEvent() throws Exception {
        UUID sub = UUID.randomUUID();
        mvc.perform(get("/api/v1/donors/me").with(jwt()
                        .jwt(j -> j.subject(sub.toString()).claim("privacy_consent", "accepted").claim("idp", "google"))
                        .authorities(new SimpleGrantedAuthority("ROLE_donor"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consents.length()").value(1));

        String payload = jdbc.sql("SELECT payload::text FROM outbox WHERE aggregate_id = "
                + "(SELECT id::text FROM donor WHERE user_id = ?)").param(sub).query(String.class).single();
        assertThat(payload).contains("GOOGLE");
    }

    @Test
    void withoutTheDonorRoleItIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/donors/me").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("privacy_consent", List.of("accepted")))
                        .authorities(new SimpleGrantedAuthority("ROLE_hospital"))))
                .andExpect(status().isForbidden()).andExpect(content().string(""));
    }

    @Test
    void withoutATokenItIsUnauthorizedWithoutBody() throws Exception {
        // Spring Security answers 401/403 itself: empty body, not problem+json (the OpenAPI says so).
        mvc.perform(get("/api/v1/donors/me")).andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate")).andExpect(content().string(""));
    }
}
