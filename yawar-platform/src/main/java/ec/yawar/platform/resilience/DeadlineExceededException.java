package ec.yawar.platform.resilience;

/** The request budget ran out before or during an external call; never retried. */
public class DeadlineExceededException extends RuntimeException {

    public DeadlineExceededException(String message) {
        super(message);
    }
}
