# deploy/ad

Samba AD domain controller `yawar.lab` (NetBIOS `YAWAR`) with the 10 staff users (YAW-30, ADR-042). Keycloak federates it
over LDAPS (YAW-32): see [`deploy/keycloak/README.md`](../keycloak/README.md#staff-accounts-from-active-directory-yaw-32).

## Run it (local, docker compose profile `ad`)
```bash
make up PROFILE=ad      # builds deploy/ad/Dockerfile, provisions yawar.lab on the first start
make ad-seed            # OUs, groups, 10 users (idempotent)
make ad-test            # simple bind over LDAPS for the 10 users -> "10/10 binds OK"
make ad-users           # users with group and site (no passwords)
make ad-cert            # lab CA -> deploy/ad/ca/ad-ca.pem (Keycloak truststore, YAW-32; run before make up)
```
`AD_ADMIN_PASSWORD` (must meet AD complexity) is read from `deploy/local/.env`; see `.env.example`. `make reset`
deletes the domain volume; the next `make up PROFILE=ad` provisions again (then run `make ad-seed`).

## Directory layout
```
DC=yawar,DC=lab
└── OU=Yawar
    ├── OU=Personal   users yw.*
    ├── OU=Servicios  svc.keycloak (read-only bind account of the Keycloak federation, YAW-32)
    └── OU=Grupos     GG-YAW-ADMINISTRATOR, -AUDITOR, -RECRUITMENT, -LOGISTICS, -HOSPITAL
```
| User | Group | Site |
|---|---|---|
| yw.admin01 | ADMINISTRATOR | quito-hcam |
| yw.auditor01 | AUDITOR | quito-hee |
| yw.recruit01 / 02 / 03 | RECRUITMENT | quito-hcam / quito-hee / guayaquil-htmc |
| yw.logistics01 / 02 / 03 | LOGISTICS | quito-hcam / quito-hee / guayaquil-htmc |
| yw.hospital01 / 02 | HOSPITAL | quito-hcam / guayaquil-htmc |

The source is `users.csv`. Each user has `givenName`, `sn`, `displayName` and `mail` = `<user>@yawar.lab`; the logon
name is `<user>@yawar.lab` (`sAMAccountName` = `<user>`).

### Site attribute
The site is stored in the existing AD attribute **`physicalDeliveryOfficeName`** (the "Office" field), so no schema
extension is needed. Keycloak maps it to the `site` user attribute and token claim (YAW-32).

## Seed script
`deploy/ad/seed-users.sh` runs `samba-tool` inside the container (`docker compose exec`). It is idempotent: a second run
prints `Done: 0 change(s).` Existing passwords are never touched.
- `make ad-seed ARGS=--reset-passwords` sets new random passwords for all users.
- `make ad-seed ARGS=--must-change` makes new (or reset) passwords expire at the next login. It is **off by default**:
  the LDAP bind tests and the Keycloak federation demo need usable passwords. Jira asks for a change at first login; turn
  the flag on for any shared environment.
- Passwords are random (AD complexity) and written to `deploy/ad/.secrets/passwords.env` (`YW_ADMIN01=...`, mode 600,
  git-ignored). The script never prints them.

## LDAPS and the certificate
Only LDAPS 636 is published, bound to `127.0.0.1` (`AD_LDAPS_PORT`). On the compose network use `ldaps://samba-ad:636`.
The first start creates a lab CA and a server certificate (SAN `samba-ad`, `samba-ad.yawar.lab`, `localhost`) in the
volume (`/var/lib/samba/tls`) and Samba is configured to use them (Samba's own auto-generated certificate has no
`samba-ad` name, which Keycloak's hostname check would reject). `make ad-cert` exports the CA (only the public certificate) to `deploy/ad/ca/ad-ca.pem`, which Keycloak mounts.
Plain LDAP 389 is not published. Simple bind with `ldapwhoami` does not work against Samba (extended operation not
implemented): `ad-test` binds and searches the user's own entry.

## Image
`Dockerfile`: `debian:12.13-slim` plus the Debian Samba 4.17 packages. NT ACLs are kept in `posix:eadb` because Docker
Desktop denies `security.*` extended attributes (provisioning fails otherwise). This is a lab image, not for production.

## Kubernetes
The StatefulSet, Service and PVC arrive with YAW-119 / YAW-219 (qa on AWS). The same image and `entrypoint.sh` apply.
