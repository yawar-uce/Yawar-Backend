package ec.yawar.donors.domain;

import java.util.Objects;

/** Donor aggregate. Holds no name or email: identity data stays in Keycloak (LOPDP minimization). */
public record Donor(DonorId id, UserId userId, boolean profileComplete, long version) {

    public Donor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
    }

    /** First profile of an account: incomplete, version 0. */
    public static Donor register(UserId userId) {
        return new Donor(DonorId.newId(), userId, false, 0);
    }
}
