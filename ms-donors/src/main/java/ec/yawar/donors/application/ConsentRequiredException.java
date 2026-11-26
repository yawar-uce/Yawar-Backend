package ec.yawar.donors.application;

/** The token carries no accepted privacy consent: no donor profile is created (HU-01 scenario 3). */
public class ConsentRequiredException extends RuntimeException {

    public ConsentRequiredException() {
        super("privacy consent is required to create a donor profile");
    }
}
