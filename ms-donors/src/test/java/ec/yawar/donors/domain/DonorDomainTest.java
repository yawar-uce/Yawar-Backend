package ec.yawar.donors.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DonorDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    @Test
    void registeredDonorIsIncompleteAtVersionZero() {
        Donor donor = Donor.register(new UserId(UUID.randomUUID()));

        assertThat(donor.profileComplete()).isFalse();
        assertThat(donor.version()).isZero();
    }

    @Test
    void grantedConsentIsActiveAndRevokedOneIsNot() {
        Consent granted = Consent.grant(ConsentPurpose.DATA_PROCESSING, "2026-1", NOW);

        assertThat(granted.isActive()).isTrue();
        assertThat(new Consent(ConsentPurpose.DATA_PROCESSING, "2026-1", NOW, NOW.plusSeconds(1)).isActive()).isFalse();
    }

    @Test
    void consentRejectsBlankPolicyVersionAndRevocationBeforeGrant() {
        assertThatThrownBy(() -> Consent.grant(ConsentPurpose.DATA_PROCESSING, " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Consent(ConsentPurpose.DATA_PROCESSING, "1", NOW, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void donorRegisteredCarriesNoIdentityData() {
        UserId user = new UserId(UUID.randomUUID());
        Donor donor = Donor.register(user);

        DonorRegistered event = DonorRegistered.of(donor, RegistrationSource.GOOGLE,
                Consent.grant(ConsentPurpose.DATA_PROCESSING, "2026-1", NOW));

        assertThat(event.donorId()).isEqualTo(donor.id().value());
        assertThat(event.registrationSource()).isEqualTo(RegistrationSource.GOOGLE);
        assertThat(event.toString()).doesNotContain(user.toString());
        assertThat(DonorRegistered.class.getRecordComponents()).extracting("name")
                .containsExactly("donorId", "registrationSource", "consentPurposes", "policyVersion", "registeredAt");
    }
}
