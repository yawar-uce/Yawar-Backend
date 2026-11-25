package ec.yawar.platform.inbox;

import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.Message;

/** Counts previous failed deliveries from the broker's {@code x-death} header. */
public final class DeliveryAttempts {

    private DeliveryAttempts() {
    }

    /**
     * Number of times this message was already rejected from {@code queue} (0 on the first delivery).
     */
    public static long previousFailures(Message message, String queue) {
        List<Map<String, ?>> deaths = message.getMessageProperties().getXDeathHeader();
        if (deaths == null) {
            return 0;
        }
        long failures = 0;
        for (Map<String, ?> death : deaths) {
            if (queue.equals(String.valueOf(death.get("queue"))) && "rejected".equals(String.valueOf(death.get("reason")))
                    && death.get("count") instanceof Number count) {
                failures += count.longValue();
            }
        }
        return failures;
    }
}
