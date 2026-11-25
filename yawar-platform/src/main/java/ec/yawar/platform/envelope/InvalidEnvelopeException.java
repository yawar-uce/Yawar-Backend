package ec.yawar.platform.envelope;

/** The message body cannot be read as a Yawar envelope; such a message goes straight to the DLQ. */
public class InvalidEnvelopeException extends RuntimeException {

    public InvalidEnvelopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
