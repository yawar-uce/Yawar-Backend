package ec.yawar.donors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** HU-01 scenario 3: without the privacy consent there is no profile, only an explained refusal. */
class Hu01Sc3AcceptanceIT extends AbstractDonorsIT {

    @Test
    void hu01_sc3_tokenWithoutConsent_returns422AndCreatesNoDonor() throws Exception {
        UUID sub = UUID.randomUUID();

        mvc.perform(get("/api/v1/donors/me").with(donorTokenWithoutConsent(sub)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("consentimiento")));

        assertThat(count("donor", "user_id = ?", sub)).isZero();
    }
}
