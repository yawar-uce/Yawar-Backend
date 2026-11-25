package ec.yawar.donors.domain;

import java.util.Objects;
import java.util.UUID;

/** Keycloak subject ({@code sub}) of the account that owns a donor profile (resource ownership, T01). */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "user id");
    }

    /** @throws IllegalArgumentException when the subject is not a UUID (Keycloak subjects always are) */
    public static UserId fromSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("token subject is required");
        }
        return new UserId(UUID.fromString(subject));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
