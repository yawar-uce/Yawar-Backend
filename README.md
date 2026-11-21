# Yawar — Backend

Monorepo of the Yawar blood-donation platform (Ecuador). Java 21 / Spring Boot, hexagonal architecture.

## Layout
```
services/          one folder per microservice (ms-donantes, ...)
libs/              shared libraries (yawar-platform: outbox/inbox)
deploy/helm/       Helm chart yawar-service
infra/terraform/   AWS (ECR, EC2/K8s, RDS) and k8s-platform roots
docs/              branching, ADRs, QA reports
.github/           workflows, PR template, CODEOWNERS
```

## Sprint 2 microservices
| Service | Description | Jira |
|---|---|---|
| Identity (Keycloak + API Gateway) | Login, roles, AD federation | EP-07 |
| `ms-donantes` | Donor registration and profile | EP-01 |

## Workflow
See [docs/BRANCHING.md](docs/BRANCHING.md): `feature/YAW-xx` → `dev` → `qa` → `production`.
Promotion to `production` requires approval from @JuanGuevara90 (`professor-approval` check).
