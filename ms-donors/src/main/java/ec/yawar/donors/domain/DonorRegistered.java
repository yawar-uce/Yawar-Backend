package ec.yawar.donors.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Event: a donor profile was created. Only IDs and consent metadata: no userId, email or name (LOPDP). */
public record DonorRegistered(
        UUID donorId,
        RegistrationSource registrationSource,
        List<ConsentPurpose> consentPurposes,
        String policyVersion,
        Instant registeredAt) {

    public static DonorRegistered of(Donor donor, RegistrationSource source, Consent consent) {
        return new DonorRegistered(donor.id().value(), source, List.of(consent.purpose()), consent.policyVersion(),
                consent.grantedAt());
    }
}
