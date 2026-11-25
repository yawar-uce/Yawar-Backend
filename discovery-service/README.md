# discovery-service

Eureka server (YAW-217): registry of the Yawar services. Every service registers with
`eureka.instance.prefer-ip-address: true` (from `yawar-config/application.yml`), and the API Gateway resolves
its `lb://ms-donors` routes through it. Spring Boot 4.1, Spring Cloud 2025.1, port **8761**.

| Path | Auth | Purpose |
|---|---|---|
| `/` | Basic | dashboard: registered instances and their status |
| `/eureka/**` | Basic | registry API used by the services (register, renew, fetch) |
| `/health/live`, `/health/ready` | none | Kubernetes probes |

Clients use `eureka.client.service-url.defaultZone=http://${EUREKA_USER}:${EUREKA_PASSWORD}@<host>:8761/eureka/`.
Fallback when the registry is down: services keep their cached registry (30 s refresh), and inside Kubernetes the
Service DNS names keep working.

| Variable | Meaning |
|---|---|
| `EUREKA_USER`, `EUREKA_PASSWORD` | Basic credentials (required, no default) |
| `EUREKA_SELF_PRESERVATION` | `true` by default; `false` in dev/kind, where instances come and go |
| `CONFIG_SERVER_URL`, `CONFIG_SERVER_USER`, `CONFIG_SERVER_PASSWORD` | optional configuration import |

```bash
make up PROFILE=platform              # builds the image and starts it with the local stack
make test-it S=discovery-service      # integration tests (random port, no containers needed)
cd discovery-service && ./mvnw verify # standalone build
```
