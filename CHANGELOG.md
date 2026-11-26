# Changelog

All notable changes to the Yawar backend are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions per microservice (SemVer).

## [Unreleased]
### Added
- Repository setup: branching flow (dev → qa → production), rulesets, CODEOWNERS, CI and branch-guard (YAW-10).
- Jira ↔ GitHub integration pilot (YAW-58).
- Monorepo layout and local docker compose environment (PostgreSQL 16 with donors_db, RabbitMQ with vhosts yawar-dev/yawar-qa, Keycloak, Mailpit) with healthchecks and Makefile targets up/down/reset/ps/logs/psql (YAW-11).
- Grafana otel-lgtm container in the local compose (Grafana on 3000, OTLP 4317/4318, bound to 127.0.0.1) for traces, logs and metrics (YAW-11).
- Local Kubernetes with kind: cluster `yawar` (ingress-ready 80/443), local registry `localhost:5001`, namespaces `yawar-dev` and `yawar-qa`, targets kind-up/kind-down/kind-smoke (YAW-14).
- Keycloak realms as code `yawar-dev` and `yawar-qa` (keycloak-config-cli): self-registration with required privacy consent, email verification through Mailpit, roles donor/recruitment/logistics/hospital/administrator/auditor/agent (new users get donor only), public client `yawar-web` with Authorization Code + PKCE S256, Google identity provider from environment variables; targets keycloak-import and keycloak-check (YAW-31).
- `ms-donors` 0.1.0-SNAPSHOT skeleton: hexagonal layout checked by ArchUnit, Flyway schema of `donors_db` (donor with encrypted blood type, key id and version, consent with one active per purpose, last_donation projection, outbox/inbox from yawar-platform), Keycloak resource server (issuer, audience `yawar-api`, realm roles), config-server import, Eureka registration, probes, OpenAPI, OTLP traces and metrics, Dockerfile, compose profile `platform`, Helm values (YAW-36).
- `config-server` 0.1.0-SNAPSHOT: Spring Cloud Config Server (git backend `yawar-uce/yawar-config`, native backend for local dev), HTTP Basic, Spring Cloud Bus refresh over RabbitMQ (`POST /busrefresh`), probes `/health/live` and `/health/ready`, Dockerfile, compose profile `platform`, Helm values (YAW-216).
- `yawar-platform` 0.1.0-SNAPSHOT: Yawar message envelope, transactional outbox with `FOR UPDATE SKIP LOCKED` relay and publisher confirms, idempotent inbox + `BaseConsumer` (retry queue, DLQ after 5 attempts), quorum queue topology, correlation id and W3C trace propagation (HTTP, outbox, AMQP), Resilience4j `ExternalCallExecutor` and `X-Deadline-Ms` deadline for external adapters (YAW-12).
- `discovery-service` 0.1.0-SNAPSHOT: Eureka server (HTTP Basic on dashboard and registry, CSRF ignored only for `/eureka/**`, probes `/health/live` and `/health/ready`, optional config import), Dockerfile, compose profile `platform`, Helm values (YAW-217).
- `api-gateway` 0.1.0-SNAPSHOT: Spring Cloud Gateway (WebFlux) with `lb://ms-donors` route under `/api/v1/donors/**`, Keycloak JWT validation (issuer, audience `yawar-api`, 30 s skew, cached JWKS), role-based access (everything not listed is denied), 60 requests/minute per user in Redis with local in-memory fallback, circuit breaker with 503 fallback, CORS per environment, generated `X-Correlation-Id` and `traceparent`, RFC 7807 errors, probes, Dockerfile, compose profiles `gateway`/`platform` and Redis service, Helm values, ADR-045 (YAW-33).
- Samba AD domain controller `yawar.lab` for staff accounts: compose profile `ad` (pinned Debian 12.13 build, LDAPS 636 on 127.0.0.1, lab CA), OU=Yawar/Personal/Grupos, 5 `GG-YAW-*` groups, 10 `yw.*` users with site in `physicalDeliveryOfficeName`, idempotent `seed-users.sh` with random passwords in git-ignored `deploy/ad/.secrets/`, targets ad-up, ad-seed, ad-test, ad-users, ad-cert (YAW-30).
- Sign-up with email and password (HU-01): `GET /api/v1/donors/me` in ms-donors creates the donor, its `DATA_PROCESSING` consent and the `DonorRegistered` outbox event on the first call (422 without the `privacy_consent` claim, ETag and `If-None-Match` 304); Keycloak realms with Spanish/English messages for duplicate email and missing consent, default role donor; ADR-046 (YAW-37).

### Changed
- CI: per-module JaCoCo summary and `coverage-reports` artifact, test reports on failure, Trivy fs scan skipping build output, new `images` job that builds every module image and scans it with Trivy (HIGH/CRITICAL fails), cancellation of superseded runs (YAW-15).
- Local RabbitMQ upgraded from 3.13 to 4.2 (`rabbitmq:4.2.9-management-alpine`); run `make reset` once to drop the old 3.13 data volume (YAW-11).
- Local vhosts `yawar-dev` and `yawar-qa` default to quorum queues (YAW-11).
- Repository layout: one top-level folder per module (`yawar-platform/`, `config-server/`, `discovery-service/`, `api-gateway/`, `ms-<name>/`) instead of `services/` and `libs/`; root aggregator `pom.xml` with Maven wrapper 3.9.16; CI builds everything with `./mvnw verify`; Makefile targets build/test/test-it/acceptance/contract/coverage with `S=<folder>` (YAW-11).
