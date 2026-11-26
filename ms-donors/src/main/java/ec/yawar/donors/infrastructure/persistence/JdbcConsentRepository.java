package ec.yawar.donors.infrastructure.persistence;

import ec.yawar.donors.application.port.ConsentRepository;
import ec.yawar.donors.domain.Consent;
import ec.yawar.donors.domain.ConsentPurpose;
import ec.yawar.donors.domain.DonorId;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcConsentRepository implements ConsentRepository {

    private final JdbcClient jdbc;

    JdbcConsentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(DonorId donorId, Consent consent) {
        jdbc.sql("INSERT INTO consent (id, donor_id, purpose, policy_version, granted_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), donorId.value(), consent.purpose().name(), consent.policyVersion(),
                        Timestamp.from(consent.grantedAt()),
                        consent.revokedAt() == null ? null : Timestamp.from(consent.revokedAt()))
                .update();
    }

    @Override
    public List<Consent> findByDonor(DonorId donorId) {
        return jdbc.sql("SELECT purpose, policy_version, granted_at, revoked_at FROM consent "
                        + "WHERE donor_id = ? ORDER BY granted_at, id")
                .param(donorId.value())
                .query((rs, n) -> {
                    Timestamp revoked = rs.getTimestamp("revoked_at");
                    return new Consent(ConsentPurpose.valueOf(rs.getString("purpose")), rs.getString("policy_version"),
                            rs.getTimestamp("granted_at").toInstant(), revoked == null ? null : revoked.toInstant());
                })
                .list();
    }
}
