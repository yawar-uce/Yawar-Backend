package ec.yawar.donors.application.port;

import ec.yawar.donors.domain.Donor;
import ec.yawar.donors.domain.UserId;
import java.util.Optional;

public interface DonorRepository {

    /** Inserts the donor unless the account already has one ({@code ON CONFLICT DO NOTHING}). */
    boolean insertIfAbsent(Donor donor);

    Optional<Donor> findByUserId(UserId userId);
}
