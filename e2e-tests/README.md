# e2e-tests

API-level end-to-end tests (YAW-47): REST Assured + JUnit 5 + AssertJ against the **running stack**
(Keycloak -> API Gateway -> ms-donors -> PostgreSQL). There is no web app yet, so the flows are driven through the
gateway's REST API; when the web app exists, Playwright covers the browser flows and these tests stay as the fast
API smoke.

The module is **standalone** (own `pom.xml` and Maven wrapper) and is not in the root aggregator's default modules,
so `./mvnw verify` at the root never needs the stack. The root pom lists it under the `e2e` profile only.

## Run it locally
```bash
make up PROFILE=platform     # config-server, Eureka, API Gateway, ms-donors + shared infrastructure
make keycloak-import         # realm yawar-dev (not needed if the stack just started: keycloak-config already applied it)
make e2e                     # = ./mvnw -B -f e2e-tests/pom.xml verify with the variables of deploy/local/.env
```
Reports: `e2e-tests/target/failsafe-reports/` (JUnit XML). Tests are `*E2E.java` and run in failsafe's
`integration-test` phase; `-DskipTests` only compiles them.

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8000` (`GATEWAY_PORT`) | API Gateway |
| `KEYCLOAK_URL` | `http://localhost:8080` (`KEYCLOAK_PORT`) | Keycloak; must be the host the services expect as token issuer (`KEYCLOAK_ISSUER_URI`) |
| `REALM` | `yawar-dev` | realm of the tokens |
| `KEYCLOAK_ADMIN_USER`, `KEYCLOAK_ADMIN_PASSWORD` | none (required; in `deploy/local/.env`) | master-realm admin, only used to create and delete the test client and users |

## What the tests do
Once per run (`E2eEnvironment`): through the Keycloak admin API it creates the confidential client `yawar-e2e` and three
users (random passwords, `@example.test`): a **donor** (verified email, `privacy_consent=accepted`, default roles
so role `donor`), a **no-donor** (all realm roles removed) and a throwaway donor used to wait until the gateway
reaches ms-donors (up to 150 s: the gateway answers 401 even before ms-donors is registered in Eureka, so only a
real call proves readiness). Tokens come from the password grant on `yawar-e2e`. At the end the client and the
users are deleted (a crashed run leaves nothing that blocks the next one: a stale client is removed first).

**Why a test-only client:** `yawar-web` is a public Authorization Code + PKCE client with no direct access
grants, and it must stay that way (a password grant on it would be a hole in production). A browser login cannot be
scripted without a browser, so `yawar-e2e` exists only while the tests run, is not in the realm file, and gets the
protocol mappers and default scopes **copied from `yawar-web` at run time** (API audience `yawar-api`, `privacy_consent`,
`idp`), so its tokens have exactly the claims of a real login.

| Test | Flow |
|---|---|
| `e2e_hu01_donorFirstCall_createsProfileThroughGateway` | `GET /api/v1/donors/me` -> 200, ETag, `consents[0].purpose = DATA_PROCESSING`, `X-Correlation-Id` |
| `e2e_hu01_secondCallWithIfNoneMatch_returns304` | same ETag back -> 304, same donor id |
| `e2e_gateway_withoutToken_returns401ProblemJson` | 401, `application/problem+json`, `WWW-Authenticate: Bearer` |
| `e2e_gateway_userWithoutDonorRole_returns403` | valid token without role `donor` -> 403 |
| `e2e_gateway_corsPreflightFromMobileOrigin_allowed` | `OPTIONS` with `Origin: capacitor://localhost` -> 200 and the origin echoed |
| `e2e_gateway_corsPreflightFromUnknownOrigin_rejected` | other origin -> 403, no `Access-Control-Allow-Origin` |
| `e2e_gateway_unknownRoute_isDenied` | unlisted path -> 401 without token, 403 with a donor token (also `/fallback/**`) |

Not covered yet: the `DonorRegistered` event on RabbitMQ (`yawar.events` has no queue bound to it until a consumer
exists; the outbox row is already checked by `Hu01Sc1AcceptanceIT` in ms-donors).

Safety guard (`E2eConfig`, unit-tested without a stack): only the realms `yawar-dev` and `yawar-qa` are accepted, and
`KEYCLOAK_URL` must be `localhost`/`127.0.0.1` unless `E2E_ALLOW_REMOTE_KEYCLOAK=true` (the tests use admin rights).

Local runs leave data behind: the Keycloak client and users are deleted, but the donor rows the first `GET /donors/me`
created stay in `donors_db` (and their outbox rows). They belong to random users that no longer exist,
and `make reset` removes them together with every other local volume.

## CI
Job `e2e` of `.github/workflows/ci.yml` starts the compose profile `platform` (without Mailpit and Grafana) with a
`deploy/local/.env` of random secrets (`.github/scripts/e2e-env.sh`), imports the realm and runs `make e2e`.
config-server reads `deploy/local/e2e-config` instead of the private `yawar-uce/yawar-config` (needs a token); the
services keep their jar defaults, so the run does not depend on that repository. On failure the last 200 log lines of
every container are printed with the secrets of the `.env` replaced by `***`; the stack and its volumes are always
removed.
