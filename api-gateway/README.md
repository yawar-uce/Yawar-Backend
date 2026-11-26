# api-gateway

API Gateway of Yawar (YAW-33): the only entry point of clients (web, mobile, AI agent). Spring Cloud Gateway on
WebFlux, Spring Boot 4.1, Spring Cloud 2025.1, Java 21, port **8000**. It validates the JWT, applies role-based
access and a per-user rate limit, protects each route with a circuit breaker, applies CORS and starts the trace.
It has no business logic and no dependency on `yawar-platform` (that library is servlet and JDBC based).
Every service still validates the JWT again and checks resource ownership (defence in depth, T01).

## Request flow
```
client -> Ingress (/api) -> CorrelationIdWebFilter -> CORS -> JWT + RBAC -> RequestRateLimiter -> CircuitBreaker
       -> lb://ms-donors (Eureka) -> service
```

| Path | Access | Goes to |
|---|---|---|
| `/api/v1/donors/**` | role `donor` | `lb://ms-donors`, path unchanged (no StripPrefix), `Authorization` forwarded unchanged |
| `/health/live`, `/health/ready`, `/info` | public | the gateway itself |
| `/fallback/**` | nobody from outside | circuit breaker fallback (`forward:` does not pass through the security chain) |
| anything else | denied (401 without a token, 403 with one) | - |

Routes are in `application.yml` (`spring.cloud.gateway.server.webflux.routes`); a new service adds one route and
one `hasRole` line in `SecurityConfig`, plus a fallback mapping in `FallbackController`.

## Errors (RFC 7807, `application/problem+json`)
| Status | When | Headers |
|---|---|---|
| 401 | no token, malformed, bad signature, expired (30 s skew), wrong issuer or audience `yawar-api` | `WWW-Authenticate: Bearer` (no token) or `Bearer error="invalid_token"` |
| 403 | valid token without the role, or path not allowed | - |
| 429 | more than 60 requests/minute for the user | `X-RateLimit-Remaining`, `-Replenish-Rate`, `-Burst-Capacity`, `-Requested-Tokens`, `Retry-After` |
| 503 | ms-donors answered 502/503/504, took over 2 s, or its circuit is open | `Retry-After: 30` only when the circuit is open |

The body carries `status`, `title`, `detail`, `instance` and the `correlationId`. The reason a token failed is
never revealed. Every response, errors included, has an `X-Correlation-Id`.

## Rate limit (C-07) and its fallback
Token bucket in Redis: 1 token/s, burst 60 (60 requests per minute sustained) per key `user:<sub>` of the token.
Requests **without a bearer token** to `/api/**` are limited per IP (`ip:<address>`, same 60) by
`AnonymousRateLimitWebFilter`, which runs before Spring Security (the route filter runs after it, so it never sees
a 401). CORS preflights (`OPTIONS`) are exempt, and requests that carry a bearer header are limited per user once
authenticated; the cost of invalid tokens is bounded by the signature check and the throttled JWKS refresh below.
The address is the last `X-Forwarded-For` entry, which is the real client only if the Ingress is the one trusted proxy and
appends it (ingress-nginx `use-forwarded-headers` or the proxy protocol). Without an Ingress (docker compose) the header
is chosen by the client, so the per-IP limit can be spoofed there; it is meant for the cluster.
Spring's `RedisRateLimiter` is fail-open (on a Redis error it answers "allowed" with `X-RateLimit-Remaining: -1`);
`ResilientRateLimiter` detects that, a Redis error or a 500 ms timeout and decides with an in-memory Caffeine
bucket of the same size instead (YAW-DOC-07 §9.5). While Redis is down every pod counts on its own, so the effective
limit is up to `replicas x 60`. Redis is **not** part of readiness. Metric `yawar.gateway.ratelimit.fallback`.

## Circuit breaker
Instance `ms-donors`: 20-call window, opens at 50 % failures, stays open 30 s, 3 trial calls; time limit 2 s,
connect timeout 1 s, response timeout 2 s. Responses 500/502/503/504 count as failures. The fallback is
`503 + Retry-After: 30` (no stale data: there is nothing safe to serve for donor profiles). No retry filter: POSTs
are not idempotent, and a retried request would be counted against the user's rate limit.
Only 502/503/504 and timeouts count as failures and get the fallback; a 500 of ms-donors passes through with its
own problem+json. `Retry-After: 30` is sent only when the circuit is open.
The Keycloak fallback is the JWKS cache (`ThrottledJwkSource`): tokens signed with a cached key keep validating while
Keycloak is down. The key set is refetched at most once per 30 s when a token names an unknown `kid` (Spring's
default would refetch per request, letting anyone hammer Keycloak) and once per 5 min; the fetch has a 1 s connect /
2 s response timeout.

## CORS
Origins come from `yawar.gateway.cors.allowed-origin-patterns` (env `CORS_ALLOWED_ORIGINS`, comma separated).
Defaults for dev: `http://localhost:5173`, `http://localhost:[*]`, `http://localhost`, `https://localhost`,
`capacitor://localhost`. qa and production set their own host in yawar-config. Credentials are off (bearer tokens,
no cookies); methods GET POST PUT PATCH DELETE OPTIONS; headers `Authorization`, `Content-Type`,
`Idempotency-Key`, `If-Match`, `If-None-Match`, `traceparent`, `tracestate`; exposed `ETag`, `Location`, `X-Correlation-Id`,
`X-RateLimit-Remaining`, `Retry-After`; max age 3600 s. The gateway's headers win over any a service adds
(`DedupeResponseHeader`).

## Tracing
`CorrelationIdWebFilter` always generates a UUID `X-Correlation-Id` (a client cannot choose the id in our logs),
sends it to the service, returns it in the response and puts it in the MDC (JSON logs). Micrometer tracing sends
`traceparent` to the service and exports spans through OTLP. Known limit: a `traceparent` sent by a client is
continued, not discarded, so the gateway is not always the root of the trace; the correlation id is always ours.

## Configuration
| Variable | Meaning (default) |
|---|---|
| `GATEWAY_PORT` | `8000` |
| `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_JWK_SET_URI` | issuer as the browser sees it / JWKS URL inside the cluster (same as ms-donors); audience `yawar-api` |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | rate limiter store (`localhost`, `6379`, empty) |
| `CORS_ALLOWED_ORIGINS` | see CORS |
| `EUREKA_HOST`, `EUREKA_PORT`, `EUREKA_USER`, `EUREKA_PASSWORD` | registry used by `lb://` |
| `CONFIG_SERVER_URL`, `CONFIG_SERVER_USER`, `CONFIG_SERVER_PASSWORD`, `CONFIG_IMPORT_MODE`, `CONFIG_FAIL_FAST` | config import (optional locally, required in Kubernetes) |
| `RABBITMQ_*` | Spring Cloud Bus (configuration refresh) only |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACING_SAMPLING` | traces and metrics |

Tunables (in `application.yml`, overridable from yawar-config): `yawar.gateway.rate-limit.*`,
`yawar.gateway.circuit-breaker.*`.

## yawar-config
The repository `yawar-uce/yawar-config` is the source of the environment values; these are the proposed changes
(this module does not edit it).

`api-gateway.yml`:
```yaml
# api-gateway (Spring Cloud Gateway, YAW-33). Routes and security live in the service (application.yml and code).
server:
  port: ${GATEWAY_PORT:8000}
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8080/realms/yawar-dev}
          jwk-set-uri: ${KEYCLOAK_JWK_SET_URI:http://localhost:8080/realms/yawar-dev/protocol/openid-connect/certs}
          audiences: yawar-api
management:
  endpoint:
    health:
      group:
        ready:
          include: readinessState         # Redis is not part of readiness (local rate limit fallback)
```
`application-qa.yml` and `application-production.yml` (they apply to every service; the property is only read by the
gateway). The public web host plus the local/app origins: `http://localhost:[*]` (desktop and dev servers),
`http://localhost` and `https://localhost` (Capacitor Android, whose scheme is configurable), `capacitor://localhost`
(Capacitor iOS):
```yaml
yawar:
  gateway:
    cors:
      allowed-origin-patterns:
        - https://<host>.sslip.io
        - http://localhost:[*]
        - http://localhost
        - https://localhost
        - capacitor://localhost
```
`application-dev.yml`: nothing (the defaults in the jar are the dev origins).
The jar already has the same defaults, so `api-gateway.yml` only makes them visible and tunable per environment
(the file in the repo today sets just the port); the CORS origins of qa and production are the one change that is
required. `application.yml` of yawar-config (RabbitMQ for Bus, Eureka, probes) applies to the gateway as is.

## Run and test
```bash
make up PROFILE=gateway               # config-server, discovery-service, api-gateway (+ the shared stack and Redis)
make up PROFILE=platform              # the above plus ms-donors
curl -i http://localhost:8000/api/v1/donors/me          # 401 problem+json without a token
cd api-gateway && ./mvnw verify       # standalone; tests need Docker (Redis container, pinned redis:8.2-alpine)
```
Unit tests (`*Test`): role converter, token validators, key resolver, local bucket, resilient limiter, problem
handlers, correlation filter. Integration tests (`*IT`, WireMock for the Keycloak JWKS and for ms-donors, real Redis
by Testcontainers): `JwtValidationIT`, `RbacIT`, `RateLimitIT`, `AnonymousRateLimitIT`, `RateLimitRedisDownIT`, `ServiceErrorPassThroughIT`, `CircuitBreakerIT`,
`SlowServiceIT`, `CorsIT`, `TracingIT`.
