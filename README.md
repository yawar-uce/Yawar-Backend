# Yawar — Backend

Monorepo of the Yawar blood-donation platform (Ecuador). Java 21 / Spring Boot, hexagonal architecture.

## Layout
```
pom.xml, mvnw, .mvn/         root aggregator (packaging pom) that only lists the modules
yawar-platform/              shared library: event envelope, outbox, inbox, correlation id
config-server/ discovery-service/ api-gateway/   platform services (Sprint 2)
ms-donors/ ms-appointments/ …   one top-level folder per microservice, named exactly as in Jira
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

## Build and test

Every module is a top-level folder with its own `pom.xml`, Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/`),
`Dockerfile` and `src/`. The root `pom.xml` only lists the modules (`yawar-platform` first), so the whole
backend builds in one command:

```bash
./mvnw verify                     # every module: unit + integration tests + JaCoCo 70 % gate (what CI runs)
./mvnw -pl ms-donors -am verify   # one module plus the modules it depends on (yawar-platform)
```
A module can also be built from its own folder (`cd ms-donors && ./mvnw verify`) once the modules it depends
on are in `~/.m2` (`./mvnw -pl yawar-platform install` from the root).

The Makefile wraps the test layers; `S=<folder>` limits them to one module:

```bash
make test S=ms-donors        # unit tests (*Test)
make test-it S=ms-donors     # integration tests with Testcontainers (*IT), Docker must be running
make acceptance S=ms-donors  # one test per Gherkin scenario (*AcceptanceIT, named hu01_sc1_…)
make contract S=ms-donors    # OpenAPI/AsyncAPI contract tests (*ContractIT)
make coverage S=ms-donors    # all tests + gate; prints line coverage and the JaCoCo HTML report path
```

A new module: create the folder (name exactly as in Jira) and add it to `<modules>` in the root `pom.xml`.
Its pom must define `<skipTests>false</skipTests>` and `<skipUTs>${skipTests}</skipUTs>`, configure surefire with
`<skipTests>${skipUTs}</skipTests>` (so `-DskipTests` still skips everything)
(unit tests `*Test`), failsafe for `*IT`, and JaCoCo `prepare-agent` + `report` + `check` (LINE covered
ratio ≥ 0.70) at `verify`.

## CI (`.github/workflows/ci.yml`)

Runs on every pull request and push to `dev`, `qa` and `production`:

| Job | Steps | Gate |
|---|---|---|
| `ci` | gitleaks (whole history) → `./mvnw verify` (unit + Testcontainers integration tests of every module) → JaCoCo summary in the job page and `coverage-reports` artifact (test reports too when it fails) → Trivy filesystem scan | secrets, test failures, coverage < 70 % per module (V-01), HIGH/CRITICAL dependency with a fix (V-03) |
| `images` | `.github/scripts/build-images.sh`: builds the image of every module with a Dockerfile (repository root as context when it copies `yawar-platform`) and scans it with Trivy | HIGH/CRITICAL image vulnerability with a fix (V-03) |

Images are tagged `yawar/<module>:<commit SHA>` and not pushed here: pushing to ECR and deploying is CD (YAW-16).
Both Trivy scans use Trivy 0.70.0; every action is pinned by commit SHA and the gitleaks download is checksum-verified.
Runs of a pull request cancel the previous run of that pull request; runs on `dev`, `qa` and `production` are never
cancelled.
Run the same checks locally: `.github/scripts/coverage-summary.sh` after `make coverage`, and
`.github/scripts/build-images.sh [module]` (Docker must be running).

## Local environment

Shared infrastructure for development, defined in [deploy/local/docker-compose.yml](deploy/local/docker-compose.yml).

**Requirements:** Docker (Docker Desktop with WSL integration on Windows), GNU make, bash.

### Start
```bash
make up        # first run copies deploy/local/.env.example to deploy/local/.env (images of modules are rebuilt)
make ps        # every service must show "(healthy)"
```
`make up` waits until every container is healthy (timeout 5 min; the first run pulls the images).
Local credentials live in `deploy/local/.env` (git-ignored). Edit that file, never `.env.example`.

### Services and URLs
| Service | URL / port | Credentials (`deploy/local/.env`) |
|---|---|---|
| PostgreSQL 16 | `localhost:5432` — databases `donors_db`, `keycloak_db` | admin `POSTGRES_USER`; one owner role per database (`DONORS_DB_USER`, ...) |
| Keycloak | http://localhost:8080 (admin console at `/admin`), realm `yawar-dev` applied from [deploy/keycloak](deploy/keycloak/README.md) | `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` |
| RabbitMQ 4.2 | AMQP `localhost:5672`, UI http://localhost:15672 — vhosts `yawar-dev`, `yawar-qa` | `RABBITMQ_USER` / `RABBITMQ_PASSWORD` |
| Mailpit | SMTP `localhost:1025`, UI http://localhost:8025 | none (accepts any SMTP auth) |
| Eureka (`PROFILE=platform`) | dashboard http://localhost:8761 · registry `/eureka/apps` | `EUREKA_USER` / `EUREKA_PASSWORD` |
| ms-donors (`PROFILE=platform`) | http://localhost:8081 · `/health/ready` · `/v3/api-docs` · `/swagger-ui/index.html` | Keycloak token (realm `yawar-dev`, role `donor`) |
| Config Server (`PROFILE=platform`) | http://localhost:8888/config/ms-donors/dev · `POST /busrefresh` | read: `CONFIG_SERVER_USER` / `CONFIG_SERVER_PASSWORD` · refresh: `CONFIG_ADMIN_USER` / `CONFIG_ADMIN_PASSWORD` |
| Grafana otel-lgtm | Grafana http://localhost:3000 (traces in Tempo, logs in Loki, metrics in Prometheus); OTLP gRPC `localhost:4317`, HTTP `localhost:4318` | none (anonymous admin, bound to 127.0.0.1) |

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
- RabbitMQ keeps restarting after pulling the 4.2 upgrade: the volume still holds 3.13 data, which 4.2
  cannot start from. Run `make reset` once (it deletes the local data), then `make up`.

### Optional profiles
Compose profiles, started with `make up PROFILE=<name>`: `platform` (config-server, discovery-service and
ms-donors; the images are built from the module folders), `ad` (Samba AD) and `gateway` (API Gateway).
The config server reads `~/yawar/yawar-config` (`CONFIG_SERVER_PROFILES=native`, default) or the private repo
`yawar-uce/yawar-config` (`=git` plus `CONFIG_GIT_TOKEN` in `deploy/local/.env`).

## Local Kubernetes (kind)

A single-node [kind](https://kind.sigs.k8s.io/) cluster to try the Helm chart and Kubernetes manifests
before AWS. Defined in [deploy/kind/](deploy/kind/).

**Requirements:** Docker, kind, kubectl.

```bash
make kind-up      # cluster "yawar" + local registry localhost:5001 + namespaces (safe to re-run)
make kind-smoke   # nodes Ready, namespaces present, push an image to localhost:5001 and run it
make kind-down    # delete the cluster and the registry container (pushed images are kept)
make kind-down KEEP_REGISTRY=1   # keep the registry container running
make kind-down PURGE_REGISTRY=1  # also delete the pushed images (volume yawar-registry-data)
```
`make kind-up` also recovers after a Docker Desktop or WSL restart: it starts the stopped registry and
node containers again.

| Item | Value |
|---|---|
| Cluster / kubectl context | `yawar` / `kind-yawar` (`make kind-up` always sets it as the current context) |
| Node | 1 control-plane, label `ingress-ready=true`, host ports `127.0.0.1:80` and `127.0.0.1:443` for an ingress controller |
| Registry | container `yawar-registry` (`registry:3.1.2`), `localhost:5001` from the host; the cluster pulls `localhost:5001/<image>` from it |
| Namespaces | `yawar-dev`, `yawar-qa`, labels `app.kubernetes.io/part-of=yawar`, `yawar.io/env=<env>`; Pod Security `enforce=baseline`, `warn/audit=restricted` |

Use the registry like this:
```bash
docker tag my-image:dev localhost:5001/yawar/my-image:dev
docker push localhost:5001/yawar/my-image:dev
kubectl --context kind-yawar -n yawar-dev run demo --image=localhost:5001/yawar/my-image:dev
```
If ports 80 or 443 are already taken on the host, `make kind-up` fails while it creates the cluster.
Free the port, or change `hostPort` in `deploy/kind/cluster.yaml` locally.

## Workflow
See [docs/BRANCHING.md](docs/BRANCHING.md): `feature/YAW-xx` → `dev` → `qa` → `production`.
Promotion to `production` requires the professor's approval (`professor-approval` check).
