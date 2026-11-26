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
- **Sign-up messages (HU-01):** a repeated email answers "Este correo ya está registrado. Inicia sesión o recupera tu
  contraseña." (`emailExistsMessage`/`usernameExistsMessage`) and a missing consent "Necesitamos tu consentimiento para tratar
  tus datos (LOPDP) y crear tu cuenta." (`error-user-attribute-required`, `privacyConsentRequired`), each also in English.
  They are the realm `messageBundles` (keycloak-config-cli does not update `localizationTexts` of an existing realm).
- **Data minimization:** only email, password and consent are required; first and last name are optional.
- **Roles:** `donor, recruitment, logistics, hospital, administrator, auditor` and `agent` (AI agent service
  role). Every new user gets **only** `donor` (default role); staff roles are assigned by an administrator.
- **Client `yawar-web`:** public, Authorization Code + **PKCE S256** only (no implicit, no password grant, no
  offline tokens), exact redirect URIs `KC_WEB_URL/callback` and `KC_WEB_URL/silent-check-sso.html` (the web
  app must use them, YAW-35), `fullScopeAllowed: false` with explicit role scope mappings (never `agent`), and
  audience `yawar-api` in access tokens. Roles are in `realm_access.roles`; `sub`, `email`, `email_verified`
  are present (API Gateway YAW-33, ms-donors YAW-37).
- **Google (HU-02):** identity provider `google`, disabled until `KC_GOOGLE_ENABLED=true`; client id and secret
  only from the environment (`deploy/local/.env` locally, a Kubernetes Secret in qa), `trustEmail: true`, first
  and last name from Google's `given_name`/`family_name` (default mappers of the Google provider). Its login runs
  the custom flow `yawar-first-broker-login` (see "Google sign-in flow").

## QA (pending, YAW-219 / YAW-13)
The QA Keycloak runs behind the Ingress on `https://yawar-qa-<ip>.sslip.io/auth`: it needs
`KC_HTTP_RELATIVE_PATH=/auth`, `KC_HOSTNAME=https://yawar-qa-<ip>.sslip.io/auth` and `KC_PROXY_HEADERS=xforwarded`,
and a Job that runs keycloak-config-cli with `realms/yawar-qa.yaml`. The API Gateway then uses the issuer
`https://yawar-qa-<ip>.sslip.io/auth/realms/yawar-qa`.

## Local use
```bash
make up                          # starts Keycloak and applies realms/yawar-dev.yaml (container keycloak-config)
make keycloak-import             # re-apply after editing the file (REALM=yawar-qa to try the qa file locally)
make keycloak-check              # 52 checks: settings, roles, PKCE login, token claims, sign-up with/without
                                 # consent, verification email, Google flow (fictional users, deleted afterwards)
make up PROFILE=idp-mock         # OIDC stub for the Google checks (they are skipped when it is not running)
```
Admin console: http://localhost:8080/admin (`KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`), realm `yawar-dev`.
Login page: http://localhost:8080/realms/yawar-dev/account. Verification emails: http://localhost:8025.

## Enabling Google sign-in
1. Google Cloud Console → APIs & Services → Credentials → OAuth client ID → *Web application*.
2. Authorized redirect URI: `http://localhost:8080/realms/yawar-dev/broker/google/endpoint`
   (qa: `https://yawar-qa-<ip>.sslip.io/auth/realms/yawar-qa/broker/google/endpoint`).
3. In `deploy/local/.env` (never in Git): `KC_GOOGLE_ENABLED=true`, `KC_GOOGLE_CLIENT_ID=…`,
   `KC_GOOGLE_CLIENT_SECRET=…`, then `make keycloak-import`.

Without `KC_GOOGLE_ENABLED=true` the Google button is hidden and the provider is inert.

## Google sign-in flow (`yawar-first-broker-login`)
```
review profile (update.profile.on.first.login = missing)        REQUIRED
user creation or linking                                         REQUIRED
  create user if unique                                          ALTERNATIVE  new email: user + donor role
  handle existing account: re-authenticate with the password     ALTERNATIVE  existing email: link after login
```
- **HU-02 sc.1 (new user):** the account is created with Google's name and email (`emailVerified` is true because of
  `trustEmail`) and the default role `donor`. Keycloak shows "review profile" prefilled from Google; the only thing
  missing, and the only thing the user must do, is to tick the privacy-consent checkbox (the documented exception,
  ADR-046). Nothing else is requested. Google users have no password; they can use "forgot password" later.
- **HU-02 sc.2 (existing email):** the Google login is linked to the existing user, never duplicated
  (`duplicateEmailsAllowed: false`), after that account's password is entered once. The access token then carries
  `idp=google`, which ms-donors maps to `registrationSource=GOOGLE` for new donors only.

### Account linking and takeover
Linking by email without proof is the classic account-takeover hole: an attacker registers `victim@gmail.com` with
their own password (unverified), the victim later signs in with Google, and an automatic link would leave the
attacker's password working on the victim's linked account. Safe automatic linking would need to know that the
existing account's email is verified, but Keycloak 26.8 has no built-in condition for that (`conditional-user-attribute`
cannot read `emailVerified`; it was tried and matches nothing), and the stock "Verify existing account by email"
adds no proof beyond Google's own verified email while leaving the attacker's password intact. So the flow never
auto-links: `idp-username-password-form` requires the password of the existing account. The victim does not know the
attacker's password, so nothing is linked; the legitimate owner (who set the password) links in one step. A wrong
password links nothing (checked by `hu02_sc2b_unverified_existing_account_is_not_auto_linked`). If silent linking of
verified accounts is wanted later, it needs a small custom authenticator (Keycloak SPI) and a new ADR.

### Enabling Google sign-in (Google Cloud Console)
1. APIs & Services, OAuth consent screen: external, scopes `openid`, `email`, `profile`.
2. Credentials, Create credentials, OAuth client ID, type *Web application*.
3. Authorized redirect URIs: `http://localhost:8080/realms/yawar-dev/broker/google/endpoint` (local) and
   `https://yawar-qa-<ip>.sslip.io/auth/realms/yawar-qa/broker/google/endpoint` (qa, one client or one per environment).
4. Local: put `KC_GOOGLE_ENABLED=true`, `KC_GOOGLE_CLIENT_ID`, `KC_GOOGLE_CLIENT_SECRET` in `deploy/local/.env`
   (ignored by Git), then `make keycloak-import`. qa: the same three values in the Keycloak Kubernetes Secret.

### Testing without Google
`make up PROFILE=idp-mock` starts `ghcr.io/navikt/mock-oauth2-server:3.0.1` (test only, port 8099). `make keycloak-check`
creates through the admin API an OIDC provider `google-test` with the same flow and `trustEmail`, signs in typing the
claims into the stub's form, and deletes the provider and the users afterwards (the realm files stay clean).
Checks: `hu02_sc1_*` (account created from the claims, consent only, role donor, token with `idp`),
`hu02_sc2_*` (existing account linked once, one user) and `hu02_sc2b_*` (unverified account not auto-linked).

### Manual checklist with real Google (once the client exists)
- [ ] New Google account: sign in, tick the consent, land in the web app; the user in the admin console has Google's
      name, verified email, role `donor` and a Google federated identity; `GET /donors/me` creates the donor with
      `registrationSource=GOOGLE`.
- [ ] Email registered earlier with a password: sign in with Google, enter the password, one user with the link.
- [ ] Same Google account again: straight in, no consent or password prompt.
- [ ] Not ticking the consent: the account is not created.
