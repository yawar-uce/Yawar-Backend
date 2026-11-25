package ec.yawar.donors.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void donorIdRoundTripsThroughText() {
        DonorId id = DonorId.newId();

        assertThat(DonorId.of(id.toString())).isEqualTo(id);
        assertThatThrownBy(() -> new DonorId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void userIdComesFromTheKeycloakSubject() {
        UUID sub = UUID.randomUUID();

        assertThat(UserId.fromSubject(sub.toString()).value()).isEqualTo(sub);
        assertThat(UserId.fromSubject(sub.toString()).toString()).isEqualTo(sub.toString());
    }

    @Test
    void userIdRejectsMissingOrInvalidSubject() {
        assertThatThrownBy(() -> UserId.fromSubject(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserId.fromSubject(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserId.fromSubject("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserId(null)).isInstanceOf(NullPointerException.class);
    }
}
