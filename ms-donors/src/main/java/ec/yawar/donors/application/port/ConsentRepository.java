package ec.yawar.donors.application.port;

import ec.yawar.donors.domain.Consent;
import ec.yawar.donors.domain.DonorId;
import java.util.List;

public interface ConsentRepository {

    void insert(DonorId donorId, Consent consent);

    List<Consent> findByDonor(DonorId donorId);
}
