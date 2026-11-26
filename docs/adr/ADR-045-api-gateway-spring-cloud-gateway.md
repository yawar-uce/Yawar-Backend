# ADR-045: API Gateway as Spring Cloud Gateway (WebFlux) with Eureka routes, edge-generated correlation id, per-client CORS, Redis rate limit with local fallback and `/api/v1` prefix

- Status: Accepted
- Date: 2026-11-25
- Related: YAW-33, ADR-020, ADR-031, ADR-039; YAW-DOC-07 §7.2, §9.4, §9.5; C-01, C-07; CLAUDE.md "Decisions after the documentation"

## Context
YAW-DOC-07 names an "API Gateway" as the single entry point: it validates the JWT, applies RBAC, rate limits
(60 requests/minute/user), starts the trace and routes in one hop to a service. The documentation does not fix the
product. After the documentation the project adopted Eureka and a Config Server on Kubernetes (course requirement),
Spring Boot 4.1 and Resilience4j for external adapters. The Keycloak realm issues access tokens with audience
`yawar-api` and the realm roles of the system. Browser clients (web, Capacitor app) call the API from other origins.
Every service validates the token again (T01), so the gateway must not hide the original token.

## Decision
1. **Spring Cloud Gateway, WebFlux flavour** (`spring-cloud-starter-gateway-server-webflux`), module `api-gateway`.
   Routes use `lb://<service>` resolved through Eureka, so adding or scaling a service needs no gateway change other
   than a route and a role rule. Fetching the registry is platform plumbing, not REST between services (C-02). The
   gateway does not depend on `yawar-platform` (servlet, JDBC).
2. **Security in code**: a `SecurityWebFilterChain` validates the JWT locally (RS256 keys cached from the JWKS
   endpoint, issuer, audience, 30 s skew), maps realm roles to authorities and denies every path that is not
   listed. The `Authorization` header is forwarded unchanged. Keycloak down is covered by the JWKS cache.
3. **Correlation id generated at the edge**: the gateway always creates a UUID `X-Correlation-Id` and overwrites the
   client's value, so the id in logs cannot be chosen (or used for log injection) by a caller; it is returned on
   every response including errors. W3C `traceparent` is propagated by Micrometer tracing (ADR-039).
4. **CORS at the gateway only**, from the list `yawar.gateway.cors.allowed-origin-patterns` (one list per
   environment, set in yawar-config; defaults are the local dev origins including `capacitor://localhost`).
   Credentials are not allowed because the API uses bearer tokens, not cookies. Services do not add CORS headers; if
   they do, the gateway's win.
5. **Rate limit in Redis with a local fallback**: token bucket 1/s, burst 60, key `user:<sub>` else the address
   appended by the Ingress; the trust in that header assumes the Ingress appends the real client address
   (ingress-nginx `use-forwarded-headers` or the proxy protocol) and does not hold without an Ingress (docker compose).
   Requests without a bearer token are limited per IP by a filter that runs before Spring Security, because the route
   filter runs after authentication. Spring's Redis limiter fails open, so a wrapper falls back to an in-memory bucket per pod
   with the same limits when Redis errors or exceeds 500 ms (YAW-DOC-07 §9.5 lists "Redis -> DB unique constraint"
   for idempotency; for the rate limit the DB is not an option). Redis is not part of readiness.
6. **Circuit breaker per route** with the §9.4 numbers and a `503 + Retry-After` problem+json fallback; no retry
   filter (POSTs are not idempotent and retries would consume the user's quota). Only 502/503/504 and timeouts
   trigger the fallback; a 500 of the service passes through. The JWKS is refetched at most once per 30 s, so tokens
   with random key ids cannot turn the gateway into a load generator against Keycloak.
7. **Public API prefix `/api/v1`** kept end to end: the gateway does not strip it, so a service's own OpenAPI
   paths, logs and tests match what clients call, and a future `/api/v2` can run beside it.

## Consequences
- (+) One place for edge concerns; services stay simple and still validate tokens themselves.
- (+) New services are reachable by adding a route and a role rule; Eureka provides instances and load balancing.
- (+) A Redis or Keycloak outage degrades (per-pod limit, cached keys) instead of stopping the API.
- (-) While Redis is down the effective limit is up to `replicas x 60` per minute.
- (-) A `traceparent` sent by a client is continued, so the gateway is not always the trace root; the correlation id
  is always generated here.
- (-) The routes list and the role rules live in two places (`application.yml`, `SecurityConfig`); a test per route
  group (`RbacIT`) guards them.
- (-) The gateway is reactive: code in it must never block; there is no business logic or JDBC in it by design.

## Alternatives considered
- A third-party API gateway product, NGINX Ingress only, or Envoy: not in the course stack; per-user rate limits and JWT role rules need plugins
  or extra components.
- Spring Cloud Gateway MVC: no reactive circuit breaker/limiter integration with the same maturity; the gateway is
  I/O bound and benefits from non-blocking.
- Static `http://service:port` URIs instead of `lb://`: simpler, but bypasses the registry decided after the docs.
- Trusting a client `X-Correlation-Id`: easier to correlate with client logs, but not trustworthy; the client can
  read the id the gateway returns.
- Fail-open rate limit on Redis failure: no protection exactly when the system is degraded.
