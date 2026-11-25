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
  The access token carries it as claim `privacy_consent`. Keycloak only gates sign-up: **ms-donors is the
  system of record** for consents (purpose, policy version, timestamp) and revocations, recording the consent
  when it first sees the claim on `GET /donors/me` (YAW-37/38) without calling Keycloak (C-02).
- **Data minimization:** only email, password and consent are required; first and last name are optional.
- **Roles:** `donor, recruitment, logistics, hospital, administrator, auditor` and `agent` (AI agent service
  role). Every new user gets **only** `donor` (default role); staff roles are assigned by an administrator.
- **Client `yawar-web`:** public, Authorization Code + **PKCE S256** only (no implicit, no password grant, no
  offline tokens), exact redirect URIs `KC_WEB_URL/callback` and `KC_WEB_URL/silent-check-sso.html` (the web
  app must use them, YAW-35), `fullScopeAllowed: false` with explicit role scope mappings (never `agent`), and
  audience `yawar-api` in access tokens. Roles are in `realm_access.roles`; `sub`, `email`, `email_verified`
  are present (API Gateway YAW-33, ms-donors YAW-37).
- **Google (HU-02):** identity provider `google`, disabled until `KC_GOOGLE_ENABLED=true`; client id and secret
  only from the environment (`deploy/local/.env` locally, a Kubernetes Secret in qa). `trustEmail` links a Google
  login to an existing account with the same verified email through the "first broker login" flow.

## QA (pending, YAW-219 / YAW-13)
The QA Keycloak runs behind the Ingress on `https://yawar-qa-<ip>.sslip.io/auth`: it needs
`KC_HTTP_RELATIVE_PATH=/auth`, `KC_HOSTNAME=https://yawar-qa-<ip>.sslip.io/auth` and `KC_PROXY_HEADERS=xforwarded`,
and a Job that runs keycloak-config-cli with `realms/yawar-qa.yaml`. The API Gateway then uses the issuer
`https://yawar-qa-<ip>.sslip.io/auth/realms/yawar-qa`.

## Local use
```bash
make up                          # starts Keycloak and applies realms/yawar-dev.yaml (container keycloak-config)
make keycloak-import             # re-apply after editing the file (REALM=yawar-qa to try the qa file locally)
make keycloak-check              # 41 checks: settings, roles, PKCE login, token claims, sign-up with/without
                                 # consent, verification email (fictional users, deleted afterwards)
```
Admin console: http://localhost:8080/admin (`KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`), realm `yawar-dev`.
Login page: http://localhost:8080/realms/yawar-dev/account. Verification emails: http://localhost:8025.

## Enabling Google sign-in
1. Google Cloud Console → APIs & Services → Credentials → OAuth client ID → *Web application*.
2. Authorized redirect URI: `http://localhost:8080/realms/yawar-dev/broker/google/endpoint`
   (qa: `https://yawar-qa-<ip>.sslip.io/auth/realms/yawar-qa/broker/google/endpoint`).
3. In `deploy/local/.env` (never in Git): `KC_GOOGLE_ENABLED=true`, `KC_GOOGLE_CLIENT_ID=…`,
   `KC_GOOGLE_CLIENT_SECRET=…`, then `make keycloak-import`.

`trustEmail: true` marks the Google email as verified; linking to an existing local account goes through the
"first broker login" flow. The case "local account registered with that email but never verified" is tested
with HU-02 (YAW-38) once a Google client exists.
