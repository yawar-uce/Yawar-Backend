# yawar-platform

Shared library of every Yawar microservice (ADR-026): message envelope, transactional outbox + relay,
idempotent inbox + `BaseConsumer`, correlation/trace propagation and resilience helpers for external
adapters. Spring Boot 4.1 auto-configuration: add the dependency and the parts whose infrastructure is
present switch on.

```xml
<dependency>
    <groupId>ec.yawar</groupId>
    <artifactId>yawar-platform</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## What a service must configure

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated   # required: the relay refuses to start without confirms
  flyway:
    locations: classpath:db/yawar-platform,classpath:db/migration   # outbox + inbox tables (V0_1)
```

The service's own migrations start at `V1`. Optional settings (defaults follow YAW-DOC-07 §9.4):

| Property | Default | Meaning |
|---|---|---|
| `yawar.platform.outbox.relay-enabled` | `true` | run the relay in this instance |
| `yawar.platform.outbox.poll-interval` | `500ms` | pause between relay runs when the outbox is empty |
| `yawar.platform.outbox.batch-size` | `100` | rows locked per run (`FOR UPDATE SKIP LOCKED LIMIT`) |
| `yawar.platform.outbox.confirm-timeout` | `5s` | max wait for the publisher confirms of a batch |
| `yawar.platform.outbox.retention` / `purge-interval` | `7d` / `1h` | published rows kept, purge frequency |
| `yawar.platform.inbox.retention` | `30d` | processed event ids remembered for deduplication |
| `yawar.platform.consumer.max-attempts` | `5` | deliveries before a message goes to the DLQ |
| `yawar.platform.resilience.timeout` | `2s` | default timeout of an external call |
| `yawar.platform.resilience.max-deadline` | `30s` | cap of the `X-Deadline-Ms` budget |

## Publishing (outbox)

Write the business change and the outbox row in the **same transaction**; never call RabbitMQ from a use case.

```java
@Transactional
public DonorId register(RegisterDonor cmd) {
    Donor donor = donors.save(Donor.register(cmd));
    outbox.append(OutboxMessage.event("donors.donor.registered", "donor", donor.id().value(),
            donor.version(), new DonorRegisteredPayload(donor.id().value())));   // IDs only (LOPDP)
    return donor.id();
}
```

The relay locks a batch with `FOR UPDATE SKIP LOCKED` (safe with several replicas), publishes each row with
the Yawar envelope, `traceparent` and `x-correlation-id` headers, and marks it published only after the
broker's confirm. Failures are retried with backoff (1 s … 60 s); rows are never dropped. Delivery is at
least once.

## Consuming (inbox + BaseConsumer)

```java
@Configuration
class Messaging {
    @Bean
    Declarables traceabilityTopology() {     // quorum queue + .retry (30 s TTL) + .dlq
        return ConsumerTopology.events("traceability.events", "donors.#", "appointments.#");
    }
}

@Component
class DonorEventsConsumer extends BaseConsumer {
    DonorEventsConsumer(ConsumerSupport support, HistoryRepository history) { super(support); … }

    @Override public String queue() { return "traceability.events"; }

    @Override protected void handle(Envelope envelope) {   // runs in the inbox transaction
        history.record(envelope.aggregateId(), envelope.eventType(), envelope.occurredAt());
    }
}
```

`BaseConsumer` (Template Method): extract the trace → open a transaction → insert `(eventId, consumer)`
into `inbox` → `handle()` → commit → ack. A duplicate is acknowledged without calling `handle()`. A failure
rolls back and goes through `<queue>.retry`; the 5th failed delivery, an invalid envelope or a
`NonRetryableMessageException` goes to `<queue>.dlq` with the `x-yawar-error` and `x-yawar-attempts` headers.
Every `BaseConsumer` bean is registered automatically (prefetch from `spring.rabbitmq.listener.simple.prefetch`).

## Correlation and tracing

- `CorrelationIdFilter` (servlet services): takes `X-Correlation-Id` from the API Gateway or creates one, puts
  it in the MDC (`correlationId`) and echoes it in the response; runs before Spring Security.
- The outbox stores `traceparent`, `correlationId` and `causationId` with the event, so the trace continues
  when the relay publishes later. `BaseConsumer` restores them: MDC entries and, with Micrometer Tracing on the
  classpath, a CONSUMER span whose parent is the received `traceparent`.

## External calls (Resilience4j) and deadline

Only for adapters to **external** dependencies (Telegram, n8n, SMTP, the external AI model provider). Yawar
services never call each other over REST (C-02).

```java
String id = calls.call("telegram", () -> telegram.send(msg), e -> emailFallback.send(msg));       // not retried
Forecast f = calls.callIdempotent("ai-provider", () -> ai.forecast(q), e -> Forecast.unavailable()); // retried
```

`ExternalCallExecutor` applies, per dependency name: timeout (2 s, or less if the request's `X-Deadline-Ms`
budget is shorter), bulkhead, circuit breaker (opens at 50 % failures over 20 calls, half-open after 30 s)
and, for idempotent calls only, retry 200/400/800 ms ± 20 %. A fallback is mandatory (YAW-DOC-07 §9.5).
Override any instance with the usual `resilience4j.*.instances.<dependency>` properties.
`DeadlineFilter` reads `X-Deadline-Ms` (remaining budget in milliseconds) for each request.

## Build and test

```bash
make test S=yawar-platform       # unit tests
make test-it S=yawar-platform    # Testcontainers PostgreSQL 16 + RabbitMQ 4.2 (Docker must be running)
make coverage S=yawar-platform   # all tests + 70 % gate, prints the coverage and the report path
cd yawar-platform && ./mvnw verify   # standalone
```

There is no Dockerfile: this module is a library (a jar used by the services), not something that runs.
