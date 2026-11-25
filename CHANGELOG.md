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
- `config-server` 0.1.0-SNAPSHOT: Spring Cloud Config Server (git backend `yawar-uce/yawar-config`, native backend for local dev), HTTP Basic, Spring Cloud Bus refresh over RabbitMQ (`POST /busrefresh`), probes `/health/live` and `/health/ready`, Dockerfile, compose profile `platform`, Helm values (YAW-216).

### Changed
- Local RabbitMQ upgraded from 3.13 to 4.2 (`rabbitmq:4.2.9-management-alpine`); run `make reset` once to drop the old 3.13 data volume (YAW-11).
- Local vhosts `yawar-dev` and `yawar-qa` default to quorum queues (YAW-11).
- Repository layout: one top-level folder per module (`yawar-platform/`, `config-server/`, `discovery-service/`, `api-gateway/`, `ms-<name>/`) instead of `services/` and `libs/`; root aggregator `pom.xml` with Maven wrapper 3.9.16; CI builds everything with `./mvnw verify`; Makefile targets build/test/test-it/acceptance/contract/coverage with `S=<folder>` (YAW-11).
