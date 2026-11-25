package ec.yawar.platform.envelope;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Serializes envelopes to the JSON wire format and back. */
public class EnvelopeCodec {

    private final ObjectMapper mapper;

    public EnvelopeCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public byte[] write(Envelope envelope) {
        return mapper.writeValueAsBytes(envelope);
    }

    /**
     * Parses a message body.
     *
     * @throws InvalidEnvelopeException when the body is not valid JSON or misses a required field
     */
    public Envelope read(byte[] body) {
        try {
            return mapper.readValue(body, Envelope.class);
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            throw new InvalidEnvelopeException("Invalid message envelope: " + e.getMessage(), e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
