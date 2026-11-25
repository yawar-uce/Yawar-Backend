package ec.yawar.donors.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifier of a donor in ms-donors; the only donor reference other services and events see. */
public record DonorId(UUID value) {

    public DonorId {
        Objects.requireNonNull(value, "donor id");
    }

    public static DonorId newId() {
        return new DonorId(UUID.randomUUID());
    }

    public static DonorId of(String value) {
        return new DonorId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
