package ec.yawar.donors.infrastructure.persistence;

import ec.yawar.donors.application.port.DonorRepository;
import ec.yawar.donors.domain.Donor;
import ec.yawar.donors.domain.DonorId;
import ec.yawar.donors.domain.UserId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDonorRepository implements DonorRepository {

    private final JdbcClient jdbc;

    JdbcDonorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(Donor donor) {
        return jdbc.sql("INSERT INTO donor (id, user_id, profile_complete, version) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (user_id) DO NOTHING")
                .params(donor.id().value(), donor.userId().value(), donor.profileComplete(), donor.version())
                .update() == 1;
    }

    @Override
    public Optional<Donor> findByUserId(UserId userId) {
        return jdbc.sql("SELECT id, user_id, profile_complete, version FROM donor WHERE user_id = ?")
                .param(userId.value())
                .query((rs, n) -> new Donor(new DonorId(rs.getObject("id", UUID.class)),
                        new UserId(rs.getObject("user_id", UUID.class)), rs.getBoolean("profile_complete"),
                        rs.getLong("version")))
                .optional();
    }
}
