package ec.yawar.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ec.yawar.platform.envelope.Envelope;
import ec.yawar.platform.envelope.EnvelopeCodec;
import ec.yawar.platform.tracing.MdcTraceContextProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class BaseConsumerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final EnvelopeCodec codec = new EnvelopeCodec(mapper);
    private InboxStore inbox;
    private RabbitTemplate rabbit;
    private PlatformTransactionManager txManager;
    private RecordingConsumer consumer;

    static class RecordingConsumer extends BaseConsumer {
        final List<Envelope> handled = new ArrayList<>();
        RuntimeException failWith;

        RecordingConsumer(ConsumerSupport support) {
            super(support);
        }

        @Override
        public String queue() {
            return "test.events";
        }

        @Override
        protected void handle(Envelope envelope) {
            if (failWith != null) {
                throw failWith;
            }
            handled.add(envelope);
        }
    }

    @BeforeEach
    void setUp() {
        inbox = mock(InboxStore.class);
        rabbit = mock(RabbitTemplate.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        consumer = new RecordingConsumer(new ConsumerSupport(inbox, new TransactionTemplate(txManager), codec,
                new MdcTraceContextProvider(), rabbit, 5));
    }

    private Message message(long previousFailures) {
        Envelope envelope = new Envelope(UUID.randomUUID(), "donors.donor.registered", "D-1", 1, Instant.now(), 1,
                "req-1", null, null, "ms-donors", mapper.readTree("{\"donorId\":\"D-1\"}"));
        MessageProperties props = new MessageProperties();
        props.setMessageId(envelope.eventId().toString());
        if (previousFailures > 0) {
            props.setHeader("x-death", List.of(Map.of("queue", "test.events", "reason", "rejected",
                    "count", previousFailures)));
        }
        return new Message(codec.write(envelope), props);
    }

    @Test
    void handlesFirstDeliveryInsideTransaction() {
        when(inbox.markReceived(any(), eq("test.events"), eq("donors.donor.registered"))).thenReturn(true);

        consumer.onMessage(message(0));

        assertThat(consumer.handled).hasSize(1);
        verify(txManager).commit(any());
    }

    @Test
    void ignoresDuplicateWithoutCallingHandle() {
        when(inbox.markReceived(any(), anyString(), anyString())).thenReturn(false);

        consumer.onMessage(message(0));

        assertThat(consumer.handled).isEmpty();
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void transientFailureRollsBackAndGoesToRetryQueue() {
        when(inbox.markReceived(any(), anyString(), anyString())).thenReturn(true);
        consumer.failWith = new IllegalStateException("db down");

        assertThatThrownBy(() -> consumer.onMessage(message(2)))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(txManager).rollback(any());
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void fifthFailedAttemptGoesToDlq() {
        when(inbox.markReceived(any(), anyString(), anyString())).thenReturn(true);
        consumer.failWith = new IllegalStateException("still down");

        consumer.onMessage(message(4));

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq(""), eq("test.events.dlq"), sent.capture());
        assertThat(sent.getValue().getMessageProperties().<Long>getHeader(BaseConsumer.HEADER_ATTEMPTS)).isEqualTo(5L);
        assertThat(sent.getValue().getMessageProperties().<String>getHeader(BaseConsumer.HEADER_ERROR))
                .contains("still down");
    }

    @Test
    void nonRetryableFailureGoesStraightToDlq() {
        when(inbox.markReceived(any(), anyString(), anyString())).thenReturn(true);
        consumer.failWith = new NonRetryableMessageException("unknown schemaVersion 9");

        consumer.onMessage(message(0));

        verify(rabbit).send(eq(""), eq("test.events.dlq"), any(Message.class));
    }

    @Test
    void invalidEnvelopeGoesStraightToDlq() {
        consumer.onMessage(new Message("{oops".getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        verify(rabbit).send(eq(""), eq("test.events.dlq"), any(Message.class));
        verify(inbox, never()).markReceived(any(), anyString(), anyString());
    }

    @Test
    void consumerNameDefaultsToQueue() {
        assertThat(consumer.consumerName()).isEqualTo("test.events");
    }

    @Test
    void supportRejectsZeroAttempts() {
        assertThatThrownBy(() -> new ConsumerSupport(inbox, new TransactionTemplate(txManager), codec,
                new MdcTraceContextProvider(), rabbit, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
