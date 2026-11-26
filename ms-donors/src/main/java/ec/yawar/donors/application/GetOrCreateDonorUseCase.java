package ec.yawar.donors.application;

import ec.yawar.donors.application.port.ConsentRepository;
import ec.yawar.donors.application.port.DonorRepository;
import ec.yawar.donors.domain.Consent;
import ec.yawar.donors.domain.ConsentPurpose;
import ec.yawar.donors.domain.Donor;
import ec.yawar.donors.domain.DonorRegistered;
import ec.yawar.donors.domain.RegistrationSource;
import ec.yawar.donors.domain.UserId;
import ec.yawar.platform.outbox.Outbox;
import ec.yawar.platform.outbox.OutboxMessage;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lazy creation of the donor profile on the first {@code GET /donors/me} (HU-01). Ownership is implicit: the
 * profile is looked up by the token's subject only. The database arbitrates concurrent first requests through
 * {@code UNIQUE(user_id)}: exactly one of them inserts the donor, its consent and the DonorRegistered outbox row.
 */
@Service
public class GetOrCreateDonorUseCase {

    static final String EVENT_TYPE = "donors.donor.registered";

    private final DonorRepository donors;
    private final ConsentRepository consents;
    private final Outbox outbox;
    private final Clock clock;
    private final String policyVersion;

    public GetOrCreateDonorUseCase(DonorRepository donors, ConsentRepository consents, Outbox outbox, Clock clock,
                                   @Value("${yawar.privacy.policy-version:2026-1}") String policyVersion) {
        this.donors = donors;
        this.consents = consents;
        this.outbox = outbox;
        this.clock = clock;
        this.policyVersion = policyVersion;
    }

    @Transactional
    public DonorProfile execute(UserId userId, boolean consentAccepted, RegistrationSource source) {
        var existing = donors.findByUserId(userId);
        if (existing.isPresent()) {
            return profile(existing.get());
        }
        if (!consentAccepted) {
            throw new ConsentRequiredException();
        }
        Donor donor = Donor.register(userId);
        if (donors.insertIfAbsent(donor)) {
            Consent consent = Consent.grant(ConsentPurpose.DATA_PROCESSING, policyVersion, clock.instant());
            consents.insert(donor.id(), consent);
            outbox.append(OutboxMessage.event(EVENT_TYPE, "donor", donor.id().toString(), donor.version(),
                    DonorRegistered.of(donor, source, consent)));
            return new DonorProfile(donor, List.of(consent));
        }
        // Lost the race: the winner's transaction has committed by now (ON CONFLICT waited for it).
        return profile(donors.findByUserId(userId).orElseThrow());
    }

    private DonorProfile profile(Donor donor) {
        return new DonorProfile(donor, consents.findByDonor(donor.id()));
    }
}
