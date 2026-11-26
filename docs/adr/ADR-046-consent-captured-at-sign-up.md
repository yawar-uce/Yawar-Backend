# ADR-046: Privacy consent captured at sign-up in Keycloak and recorded by ms-donors on the first `GET /donors/me`

- Status: Accepted
- Date: 2026-11-25
- Related: YAW-37, YAW-31; HU-01, RF-01, RF-09; RN-08; LOPDP; ADR-020 (no REST between services)

## Context
HU-01 requires that nobody creates an account without accepting the personal-data consent (LOPDP), and that the
consent is kept as evidence (purpose, policy version, timestamp) so it can later be revoked (HU-05). Identity
(email, password, names) lives in Keycloak; ms-donors owns donors and consents. ms-donors cannot call Keycloak
(C-02) and the account is created in Keycloak before ms-donors knows it. Google sign-in (HU-02) skips the
registration form.

## Decision
1. The consent is a **required Keycloak user-profile attribute** `privacy_consent` (single option `accepted`, one
   checkbox) on the sign-up form and in the "review profile" step after a Google login. Without it Keycloak creates
   no account and shows an explanatory message. First and last name stay optional (data minimization).
2. A token mapper emits the attribute as the access-token claim `privacy_consent`.
3. ms-donors creates the donor **lazily on the first `GET /api/v1/donors/me`**, only if the claim is accepted
   (otherwise 422 problem+json and no row). In one transaction it inserts the donor (`ON CONFLICT (user_id) DO
   NOTHING`), the `DATA_PROCESSING` consent with the policy version (`yawar.privacy.policy-version`) and the
   `DonorRegistered` outbox row; concurrent first requests produce exactly one of each.
4. ms-donors stores **no name or email**, and `DonorRegistered` carries only the donor id, source, consent purposes,
   policy version and time. ms-donors is the system of record for consents and revocations.

## Consequences
- No synchronous dependency between Keycloak and ms-donors; works for password and Google sign-ups alike.
- The donor row exists only after the first call to the API, so the web app must call `/donors/me` right after login.
- The Keycloak attribute only gates sign-up; later revocation lives in ms-donors and does not change the claim.
- A Keycloak message override (`error-user-attribute-required`) explains the missing consent; it applies to any
  required attribute, and only `privacy_consent` is required besides the email.
- The `ETag` of `GET /donors/me` is the donor `version`, so every consent change (grant or revoke, HU-05) must
  increment the donor `version` in the same transaction; otherwise `If-None-Match` would serve stale consents.
- `registrationSource` comes from the `idp` claim (session note `identity_provider`, mapper `identity-provider` on
  `yawar-web`); it is absent for email + password sign-in, which ms-donors records as `PASSWORD`.
