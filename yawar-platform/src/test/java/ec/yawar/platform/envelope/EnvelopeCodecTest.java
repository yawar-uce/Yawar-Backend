package ec.yawar.platform.envelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EnvelopeCodecTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final EnvelopeCodec codec = new EnvelopeCodec(mapper);

    static Envelope sample(JsonMapper mapper) {
        return new Envelope(UUID.randomUUID(), "donors.donor.registered", "D-1", 1,
                Instant.parse("2026-11-25T10:15:30Z"), 1, "req-1", null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "ms-donors",
                mapper.readTree("{\"donorId\":\"D-1\"}"));
    }

    @Test
    void roundTripKeepsEveryField() {
        Envelope envelope = sample(mapper);

        Envelope read = codec.read(codec.write(envelope));

        assertThat(read).isEqualTo(envelope);
    }

    @Test
    void writesDocumentedFieldNamesAndIsoTimestamp() {
        String json = new String(codec.write(sample(mapper)), StandardCharsets.UTF_8);

        assertThat(json).contains("\"eventId\"", "\"eventType\":\"donors.donor.registered\"",
                "\"aggregateId\":\"D-1\"", "\"aggregateVersion\":1", "\"occurredAt\":\"2026-11-25T10:15:30Z\"",
                "\"schemaVersion\":1", "\"correlationId\":\"req-1\"", "\"causationId\"", "\"traceparent\"",
                "\"producer\":\"ms-donors\"", "\"payload\":{\"donorId\":\"D-1\"}");
    }

    @Test
    void rejectsBodyThatIsNotJson() {
        assertThatThrownBy(() -> codec.read("not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidEnvelopeException.class);
    }

    @Test
    void rejectsEnvelopeWithoutRequiredField() {
        String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"x\",\"aggregateVersion\":1,"
                + "\"occurredAt\":\"2026-11-25T10:15:30Z\",\"schemaVersion\":1,\"correlationId\":\"c\","
                + "\"producer\":\"p\",\"payload\":{}}";

        assertThatThrownBy(() -> codec.read(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidEnvelopeException.class)
                .hasMessageContaining("aggregateId");
    }

    @Test
    void validatesVersions() {
        Envelope ok = sample(mapper);
        assertThatThrownBy(() -> new Envelope(ok.eventId(), ok.eventType(), ok.aggregateId(), -1, ok.occurredAt(),
                1, ok.correlationId(), null, null, ok.producer(), ok.payload()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Envelope(ok.eventId(), ok.eventType(), ok.aggregateId(), 1, ok.occurredAt(),
                0, ok.correlationId(), null, null, ok.producer(), ok.payload()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
