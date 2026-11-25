package ec.yawar.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.yawar.platform.envelope.Exchanges;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboxMessageTest {

    @Test
    void eventGoesToEventsExchangeWithTypeAsRoutingKey() {
        OutboxMessage m = OutboxMessage.event("donors.donor.registered", "donor", "D-1", 1, Map.of("donorId", "D-1"));

        assertThat(m.exchange()).isEqualTo(Exchanges.EVENTS);
        assertThat(m.routingKey()).isEqualTo("donors.donor.registered");
        assertThat(m.schemaVersion()).isEqualTo(1);
        assertThat(m.causationId()).isNull();
    }

    @Test
    void commandGoesToCommandsExchange() {
        OutboxMessage m = OutboxMessage.command("inventory.reserve", "request", "R-1", 3, Map.of("units", 2));

        assertThat(m.exchange()).isEqualTo(Exchanges.COMMANDS);
        assertThat(m.routingKey()).isEqualTo("inventory.reserve");
    }

    @Test
    void withersKeepTheRest() {
        OutboxMessage m = OutboxMessage.event("a.b.c", "x", "1", 2, Map.of())
                .withCausationId("cmd-9")
                .withSchemaVersion(2);

        assertThat(m.causationId()).isEqualTo("cmd-9");
        assertThat(m.schemaVersion()).isEqualTo(2);
        assertThat(m.aggregateVersion()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> OutboxMessage.event(" ", "x", "1", 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxMessage.event("a", "x", "1", -1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxMessage.event("a", "x", "1", 0, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OutboxMessage.event("a", "x", "1", 0, Map.of()).withSchemaVersion(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
