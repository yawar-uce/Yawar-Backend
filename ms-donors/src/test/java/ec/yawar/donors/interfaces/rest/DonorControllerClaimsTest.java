package ec.yawar.donors.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import ec.yawar.donors.domain.RegistrationSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class DonorControllerClaimsTest {

    @Test
    void consentClaimAcceptsKeycloakListStringAndBoolean() {
        assertThat(DonorController.consentAccepted(List.of("accepted"))).isTrue();
        assertThat(DonorController.consentAccepted("accepted")).isTrue();
        assertThat(DonorController.consentAccepted(true)).isTrue();
        assertThat(DonorController.consentAccepted(List.of())).isFalse();
        assertThat(DonorController.consentAccepted(List.of("declined"))).isFalse();
        assertThat(DonorController.consentAccepted(false)).isFalse();
        assertThat(DonorController.consentAccepted(null)).isFalse();
    }

    @Test
    void googleIdpMeansGoogleRegistration() {
        assertThat(DonorController.source("google")).isEqualTo(RegistrationSource.GOOGLE);
        assertThat(DonorController.source(null)).isEqualTo(RegistrationSource.PASSWORD);
    }
}
