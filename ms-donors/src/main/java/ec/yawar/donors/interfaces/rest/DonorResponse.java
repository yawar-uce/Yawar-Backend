package ec.yawar.donors.interfaces.rest;

import ec.yawar.donors.application.DonorProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Body of {@code GET /api/v1/donors/me}. */
public record DonorResponse(UUID id, boolean profileComplete, List<ConsentView> consents) {

    public record ConsentView(String purpose, String policyVersion, Instant grantedAt) {
    }

    static DonorResponse from(DonorProfile p) {
        return new DonorResponse(p.donor().id().value(), p.donor().profileComplete(),
                p.consents().stream().filter(c -> c.isActive())
                        .map(c -> new ConsentView(c.purpose().name(), c.policyVersion(), c.grantedAt())).toList());
    }
}
