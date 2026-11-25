package ec.yawar.platform.resilience;

/** An external call took longer than its timeout (or the remaining deadline). */
public class CallTimeoutException extends RuntimeException {

    public CallTimeoutException(String message) {
        super(message);
    }
}
