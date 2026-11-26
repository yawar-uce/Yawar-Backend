package ec.yawar.donors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-01 scenario 1: a donor signed up with a valid consent gets a profile, a consent and one DonorRegistered. */
class Hu01Sc1AcceptanceIT extends AbstractDonorsIT {

    @Test
    void hu01_sc1_validSignup_createsDonorWithConsentAndDonorRegisteredEvent() throws Exception {
        UUID sub = UUID.randomUUID();

        mvc.perform(get("/api/v1/donors/me").with(donorToken(sub)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.profileComplete").value(false))
                .andExpect(jsonPath("$.consents[0].purpose").value("DATA_PROCESSING"))
                .andExpect(jsonPath("$.consents[0].policyVersion").value("2026-1"));

        UUID donorId = jdbc.sql("SELECT id FROM donor WHERE user_id = ?").param(sub).query(UUID.class).single();
        assertThat(count("consent", "donor_id = ? AND purpose = 'DATA_PROCESSING' AND revoked_at IS NULL", donorId))
                .isEqualTo(1);
        List<Map<String, Object>> events = jdbc.sql("SELECT event_type, aggregate_type, payload::text AS payload "
                + "FROM outbox WHERE aggregate_id = ?").param(donorId.toString()).query().listOfRows();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("event_type", "donors.donor.registered")
                .containsEntry("aggregate_type", "donor");
        String payload = (String) events.get(0).get("payload");
        assertThat(payload).contains(donorId.toString()).contains("PASSWORD").contains("DATA_PROCESSING")
                .doesNotContain("@").doesNotContain(sub.toString());
    }
}
