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

### Changed
- Local RabbitMQ upgraded from 3.13 to 4.2 (`rabbitmq:4.2.9-management-alpine`); run `make reset` once to drop the old 3.13 data volume (YAW-11).
- Local vhosts `yawar-dev` and `yawar-qa` default to quorum queues (YAW-11).
