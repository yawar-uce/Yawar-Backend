package ec.yawar.donors.domain;

import java.time.Instant;
import java.util.Objects;

/** Evidence of a consent: purpose, version of the policy text accepted, and when. Revoked ones are kept. */
public record Consent(ConsentPurpose purpose, String policyVersion, Instant grantedAt, Instant revokedAt) {

    public Consent {
        Objects.requireNonNull(purpose, "purpose");
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policy version is required");
        }
        Objects.requireNonNull(grantedAt, "grantedAt");
        if (revokedAt != null && revokedAt.isBefore(grantedAt)) {
            throw new IllegalArgumentException("a consent cannot be revoked before it was granted");
        }
    }

    public static Consent grant(ConsentPurpose purpose, String policyVersion, Instant at) {
        return new Consent(purpose, policyVersion, at, null);
    }

    public boolean isActive() {
        return revokedAt == null;
    }
}
