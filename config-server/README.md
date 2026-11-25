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
| `GET /config/{application}/{profile}` | Basic | merged configuration (e.g. `/config/ms-donors/dev`) |
| `POST /busrefresh` | Basic | publish `RefreshRemoteApplicationEvent` on the `springCloudBus` exchange: every service reloads |
| `GET /health/live`, `/health/ready` | none | Kubernetes probes (ready includes RabbitMQ) |

Clients import it with `spring.config.import=configserver:http://config-server:8888/config` and the
credentials in `spring.cloud.config.username` / `password` (from env). The `/config` prefix keeps the
configuration endpoints away from `/health/**`.

## Environment
| Variable | Meaning |
|---|---|
| `CONFIG_SERVER_USER`, `CONFIG_SERVER_PASSWORD` | Basic credentials (required, no default) |
| `CONFIG_GIT_URI`, `CONFIG_GIT_USERNAME`, `CONFIG_GIT_TOKEN`, `CONFIG_GIT_LABEL` | git backend (token from a Secret) |
| `CONFIG_NATIVE_DIR` | native backend folder, e.g. `file:/config-repo` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` | Spring Cloud Bus |

## Run
```bash
make up PROFILE=platform           # builds the image and starts it with the local stack (native profile)
make test-it S=config-server       # Testcontainers RabbitMQ, native backend with src/test/resources/config-repo
cd config-server && ./mvnw verify  # standalone build
```
