package ec.yawar.platform.inbox;

/**
 * Thrown by {@link BaseConsumer#handle} when retrying cannot help (invalid payload, unknown schema version):
 * the message goes straight to the DLQ.
 */
public class NonRetryableMessageException extends RuntimeException {

    public NonRetryableMessageException(String message) {
        super(message);
    }

    public NonRetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
