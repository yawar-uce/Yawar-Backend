# config-server

Spring Cloud Config Server (YAW-216): serves the configuration of every Yawar service and broadcasts refreshes
over Spring Cloud Bus (RabbitMQ). Spring Boot 4.1, Spring Cloud 2025.1, port **8888**.

| Profile (`CONFIG_SERVER_PROFILES`) | Backend | Used in |
|---|---|---|
| `git` (default) | private repo `yawar-uce/yawar-config`, branch `main`, read-only token `CONFIG_GIT_TOKEN` | qa, production, demo |
| `native` | local folder `CONFIG_NATIVE_DIR` (compose mounts `~/yawar/yawar-config`) | dev without a token |

## Endpoints
| Path | Auth | Purpose |
|---|---|---|
| `GET /config/{application}/{profile}` | client or admin | merged configuration (e.g. `/config/ms-donors/dev`) |
| `POST /busrefresh` | admin | publish `RefreshRemoteApplicationEvent` on the `springCloudBus` exchange: every service reloads |
| `GET /health`, `/info` | admin | full health with components (RabbitMQ, git/native backend) |
| `GET /health/live`, `/health/ready` | none | Kubernetes probes (ready does not depend on RabbitMQ: serving config does not need it) |

Two HTTP Basic users (least privilege): the **client** user (`CONFIG_SERVER_USER`) that every service holds can
only read configuration; the **admin** user (`CONFIG_ADMIN_USER`) can also refresh the whole fleet.

Clients import it with `spring.config.import=configserver:http://config-server:8888/config` and the client
credentials in `spring.cloud.config.username` / `password` (from env). The `/config` prefix keeps the
configuration endpoints away from `/health/**`.

Client resilience (YAW-DOC-07 §9.5, set by each service, first in ms-donors / YAW-36):
`spring.cloud.config.fail-fast=true` with `spring.cloud.config.retry.*` (spring-retry: 6 attempts, 1 s → 2 s
backoff) and `spring.cloud.config.request-read-timeout=5000`. Fallback: a pod that cannot get its configuration
does not start (it never runs with guessed values); running pods keep their last configuration.

## Environment
| Variable | Meaning |
|---|---|
| `CONFIG_SERVER_USER`, `CONFIG_SERVER_PASSWORD` | client user: read configuration (required, no default) |
| `CONFIG_ADMIN_USER`, `CONFIG_ADMIN_PASSWORD` | admin user: `/busrefresh`, full `/health` (required, no default) |
| `CONFIG_GIT_URI`, `CONFIG_GIT_USERNAME`, `CONFIG_GIT_TOKEN`, `CONFIG_GIT_LABEL` | git backend (token from a Secret) |
| `CONFIG_NATIVE_DIR` | native backend folder, e.g. `file:/config-repo` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` | Spring Cloud Bus (`RABBITMQ_VHOST` has no default: `yawar-dev`, `yawar-qa`, `yawar-production`) |

Known limitation: no OpenTelemetry tracing yet in this module (ECS JSON logs only); it is added with the
observability work (YAW-19).

## Run
```bash
make up PROFILE=platform           # builds the image and starts it with the local stack (native profile)
make test-it S=config-server       # Testcontainers RabbitMQ, native backend with src/test/resources/config-repo
cd config-server && ./mvnw verify  # standalone build
```
