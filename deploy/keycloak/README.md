# deploy/keycloak

Keycloak realm configuration as code (YAW-31, EP-07 RF-01..RF-06), applied with
[keycloak-config-cli](https://github.com/adorsys/keycloak-config-cli) `6.6.0-26.8.1` (built for Keycloak 26.8).
The import is idempotent: editing a file and re-running it updates the realm in place.

| File | Realm | Web app (`KC_WEB_URL`) | Email |
|---|---|---|---|
| `realms/yawar-dev.yaml` | `yawar-dev` | `http://localhost:5173` | Mailpit (local) |
| `realms/yawar-qa.yaml` | `yawar-qa` | `https://yawar-qa-<ip>.sslip.io` (QA Secret / Helm values) | Mailpit (in-cluster) |

## What the realm defines
- **Sign-up and login:** self-registration with email as username, email verification, password reset,
  brute-force protection, password policy `length(10) and notUsername and notEmail and passwordHistory(3)`
  (Keycloak hashes with Argon2, RNF-01), access tokens of 5 minutes, Spanish UI (`es`, with `en`).
- **Privacy consent (LOPDP):** user-profile attribute `privacy_consent` (option `accepted`), required for users.
  The sign-up form refuses the registration without it, and Google users are asked for it in "review profile".
- **Roles:** `donor, recruitment, logistics, hospital, administrator, auditor` and `agent` (AI agent service
  role). Every new user gets **only** `donor` (default role); staff roles are assigned by an administrator.
- **Client `yawar-web`:** public, Authorization Code + **PKCE S256** only (no implicit, no password grant),
  redirect URIs and web origins limited to `KC_WEB_URL`, `fullScopeAllowed: false` with explicit role scope
  mappings, and audience `yawar-api` in access tokens (checked by the API Gateway, YAW-33).
- **Google (HU-02):** identity provider `google`, disabled until `KC_GOOGLE_ENABLED=true`; client id and secret
  only from the environment (`deploy/local/.env` locally, a Kubernetes Secret in qa). `trustEmail` links a Google
  login to an existing account with the same verified email through the "first broker login" flow.

## Local use
```bash
make up                          # starts Keycloak and applies realms/yawar-dev.yaml (container keycloak-config)
make keycloak-import             # re-apply after editing the file (REALM=yawar-qa to try the qa file locally)
make keycloak-check              # 35 checks: settings, roles, PKCE login, sign-up with/without consent, email
```
Admin console: http://localhost:8080/admin (`KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`), realm `yawar-dev`.
Login page: http://localhost:8080/realms/yawar-dev/account. Verification emails: http://localhost:8025.

## Enabling Google sign-in
1. Google Cloud Console → APIs & Services → Credentials → OAuth client ID → *Web application*.
2. Authorized redirect URI: `http://localhost:8080/realms/yawar-dev/broker/google/endpoint`
   (qa: `https://yawar-qa-<ip>.sslip.io/auth/realms/yawar-qa/broker/google/endpoint`).
3. In `deploy/local/.env` (never in Git): `KC_GOOGLE_ENABLED=true`, `KC_GOOGLE_CLIENT_ID=…`,
   `KC_GOOGLE_CLIENT_SECRET=…`, then `make keycloak-import`.
