# Yawar — Backend

Monorepo of the Yawar blood-donation platform (Ecuador). Java 21 / Spring Boot, hexagonal architecture.

## Layout
```
services/                    one Maven project per microservice (ms-donors, ...)
libs/yawar-platform/         shared library: event envelope, outbox, inbox, correlation id
deploy/local/                docker compose for the local environment (.env.example, init scripts)
deploy/kind/                 local Kubernetes cluster (kind)
deploy/helm/yawar-service/   shared Helm chart
deploy/keycloak/             Keycloak realm configuration as code, per environment
deploy/ad/                   Samba AD (staff directory, domain yawar.lab)
infra/terraform/             AWS (aws-lab) and k8s-platform roots, modules, tests
docs/                        branching, ADRs (docs/adr), QA plans and reports (docs/qa)
.github/                     workflows, rulesets, PR template
Makefile                     single entry point (`make help`)
```

## Sprint 2 microservices
| Service | Description | Jira |
|---|---|---|
| Identity (Keycloak + API Gateway) | Login, roles, AD federation | EP-07 |
| `ms-donors` | Donor registration and profile | EP-01 |

## Local environment

Shared infrastructure for development, defined in [deploy/local/docker-compose.yml](deploy/local/docker-compose.yml).

**Requirements:** Docker (Docker Desktop with WSL integration on Windows), GNU make, bash.

### Start
```bash
make up        # first run copies deploy/local/.env.example to deploy/local/.env
make ps        # every service must show "(healthy)"
```
`make up` waits until every container is healthy (timeout 5 min; the first run pulls the images).
Local credentials live in `deploy/local/.env` (git-ignored). Edit that file, never `.env.example`.

### Services and URLs
| Service | URL / port | Credentials (`deploy/local/.env`) |
|---|---|---|
| PostgreSQL 16 | `localhost:5432` — databases `donors_db`, `keycloak_db` | admin `POSTGRES_USER`; one owner role per database (`DONORS_DB_USER`, ...) |
| Keycloak | http://localhost:8080 (admin console at `/admin`) | `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` |
| RabbitMQ | AMQP `localhost:5672`, UI http://localhost:15672 — vhosts `yawar-dev`, `yawar-qa` | `RABBITMQ_USER` / `RABBITMQ_PASSWORD` |
| Mailpit | SMTP `localhost:1025`, UI http://localhost:8025 | none (accepts any SMTP auth) |

### Everyday commands
```bash
make logs               # follow all logs
make logs S=keycloak    # one service
make psql               # psql as admin on the "postgres" database
make psql DB=donors_db  # psql on a specific database
make psql SQL='\l'      # run a single command (here: list databases)
```

### Stop and reset
```bash
make down      # stop containers, keep data (volumes)
make reset     # stop containers and DELETE all local data; next `make up` starts clean
```
The PostgreSQL init script only runs on an empty volume: after changing database names or users in
`.env`, run `make reset`.

### Troubleshooting
- All ports are published on `127.0.0.1` only (the dummy credentials are public), so the services are
  reachable from the host but not from the local network.
- `ports are not available ... 5432`: another PostgreSQL already uses the port (on Windows, check for
  `postgresql-x64-*` services). Stop it, or set another host port in `deploy/local/.env`, e.g.
  `POSTGRES_PORT=5433`. The same works for every `*_PORT` variable.
- `make up` warns when `.env.example` is newer than your `.env`: copy any new keys into `.env`.

### Optional profiles
Later tasks add compose profiles, started with `make up PROFILE=<name>`: `ad` (Samba AD) and
`gateway` (API Gateway).

## Workflow
See [docs/BRANCHING.md](docs/BRANCHING.md): `feature/YAW-xx` → `dev` → `qa` → `production`.
Promotion to `production` requires the professor's approval (`professor-approval` check).
