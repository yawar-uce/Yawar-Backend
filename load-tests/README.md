# load-tests

k6 load test of `GET /api/v1/donors/me` through the API Gateway to ms-donors (YAW-48, V-05, RNF-05).

## Run it
```bash
make up PROFILE=platform     # stack: Keycloak, API Gateway, ms-donors and shared infrastructure
make keycloak-import
make load                    # smoke; make load PROFILE=load or PROFILE=stress for the longer ones
```
k6 runs from a pinned Docker image with the host network. Reports: `load-tests/results/summary.md` and
`summary.json` (git-ignored). A failed threshold ends with a non-zero exit code.

| Profile | Load | Passes when |
|---|---|---|
| `smoke` (CI) | 20 users, 1 min | p95 < 3 s, errors < 1 % |
| `load` | up to 100 users, about 7 min | p95 <= 2 s, errors < 1 % |
| `stress` | 100 to 300 users, about 16 min | p95 < 3 s, errors < 1 % |

Only `smoke` has been run so far. 200 and 304 count as success; 429 and 5xx are errors.

## Users and safety
The gateway limits each user to 60 requests/minute and the test does not change that: every virtual user is its own
donor and waits 1.2 s between requests. Setup creates a temporary client `yawar-load` and the donors
(`load-donor-*@example.test`) through the Keycloak admin API; teardown deletes them, and the next run removes any
left by a killed run. The donor rows stay in `donors_db` (`make reset` removes them).

The script uses admin rights, so it only accepts the realms `yawar-dev` and `yawar-qa` and a Keycloak on
`localhost`/`127.0.0.1` (`LOAD_ALLOW_REMOTE_KEYCLOAK=true` overrides it).

## CI
Job `load` of `.github/workflows/ci.yml` starts the same stack as `e2e` with random secrets, runs `make load`, adds
the table to the job summary and uploads the artifact `load-test-reports`. It is not part of the `ci` gate.
