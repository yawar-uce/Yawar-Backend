package ec.yawar.donors.application;

import ec.yawar.donors.domain.Consent;
import ec.yawar.donors.domain.Donor;
import java.util.List;

/** A donor with its consents, as returned to the owner. */
public record DonorProfile(Donor donor, List<Consent> consents) {
}
