# ms-donors

Donor profile, consents (LOPDP) and eligibility — YAW-DOC-07 §7.3.1, EP-01. Spring Boot 4.1, Java 21, port **8081**,
database `donors_db` (PostgreSQL). This is the skeleton (YAW-36); the endpoints arrive with HU-01..HU-05
(YAW-37..41).

| Documented | Value |
|---|---|
| Tables | `donor` (blood type as AES-GCM ciphertext + key id, `version` for If-Match), `consent` (one active per purpose), `last_donation` (projection of DonationRecorded), `outbox`, `inbox` |
| Endpoints | `GET /api/v1/donors/me` · `PUT /api/v1/donors/me` (If-Match) · `POST /api/v1/donors/me/consents` |
| Publishes | DonorRegistered, ProfileCompleted, EligibilityChanged, ConsentRevoked, PersonalDataAccessed (outbox) |
| Consumes | DonationRecorded (inbox, `BaseConsumer`) |

## Layout (hexagonal)
```
src/main/java/ec/yawar/donors/
  domain/          aggregates, value objects (DonorId, UserId) — plain Java, checked by ArchitectureTest
  application/     use cases and ports
  infrastructure/  persistence, outbox, consumers, security (Keycloak realm roles → ROLE_*)
  interfaces/      REST controllers, OpenAPI definition
src/main/resources/db/migration/V1__init.sql   (outbox/inbox: yawar-platform V0_1)
```

## Runtime
- **Configuration:** from config-server (`CONFIG_SERVER_URL`, `yawar-config/ms-donors.yml`, profile `YAWAR_ENV`);
  required in Kubernetes (`CONFIG_IMPORT_MODE=""`, `CONFIG_FAIL_FAST=true`: a pod without configuration does not
  start) and optional locally.
- **Registry:** registers in Eureka with its IP (`prefer-ip-address: true`).
- **Security:** Keycloak JWT, issuer `KEYCLOAK_ISSUER_URI`, keys from `KEYCLOAK_JWK_SET_URI`, audience `yawar-api`;
  `/api/v1/donors/**` needs the `donor` role, everything else except probes and OpenAPI is denied.
- **Database:** HikariCP 10 connections, `statement_timeout` 5 s, `lock_timeout` 2 s; schema only through Flyway
  (`ddl-auto: validate`).
- **Probes:** `/health/live`, `/health/ready` (PostgreSQL + RabbitMQ). **OpenAPI:** `/v3/api-docs`,
  Swagger UI at `/swagger-ui/index.html` (disabled in Kubernetes).
- **Observability:** OTLP traces and metrics to `OTEL_EXPORTER_OTLP_ENDPOINT` (Grafana otel-lgtm locally); logs are
  ECS JSON on stdout; `X-Correlation-Id` in every response.

## Build and run
```bash
make test S=ms-donors        # unit: value objects, role converter, ArchUnit hexagonal rules
make test-it S=ms-donors     # Testcontainers PostgreSQL 16 + RabbitMQ 4.2 (Docker must be running)
make coverage S=ms-donors    # all tests + 70 % gate, prints the JaCoCo report path
make up PROFILE=platform     # image built from the repo root (docker build -f ms-donors/Dockerfile .)
```
