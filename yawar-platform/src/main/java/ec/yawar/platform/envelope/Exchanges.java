package ec.yawar.platform.envelope;

/** RabbitMQ exchanges of Yawar (YAW-DOC-07 §8.2). */
public final class Exchanges {

    /** Topic exchange for domain events, routing key {@code <context>.<entity>.<fact>}. */
    public static final String EVENTS = "yawar.events";

    /** Direct exchange for commands, routing key {@code <target-service>.<action>}. */
    public static final String COMMANDS = "yawar.commands";

    private Exchanges() {
    }
}
