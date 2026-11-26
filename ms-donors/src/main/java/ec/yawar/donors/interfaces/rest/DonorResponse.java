package ec.yawar.donors.interfaces.rest;

import ec.yawar.donors.application.DonorProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Body of {@code GET /api/v1/donors/me}. */
@Schema(description = "The caller's donor profile with its active consents")
public record DonorResponse(
        @Schema(description = "Donor id") UUID id,
        @Schema(description = "True once the profile data has been completed") boolean profileComplete,
        @Schema(description = "Active consents, one per purpose") List<ConsentView> consents) {

    @Schema(description = "An active consent")
    public record ConsentView(
            @Schema(description = "Purpose the donor consented to", allowableValues = "DATA_PROCESSING") String purpose,
            @Schema(description = "Version of the privacy policy that was accepted", example = "2026-1") String policyVersion,
            @Schema(description = "When the consent was granted") Instant grantedAt) {
    }

    static DonorResponse from(DonorProfile p) {
        return new DonorResponse(p.donor().id().value(), p.donor().profileComplete(),
                p.consents().stream().filter(c -> c.isActive())
                        .map(c -> new ConsentView(c.purpose().name(), c.policyVersion(), c.grantedAt())).toList());
    }
}
