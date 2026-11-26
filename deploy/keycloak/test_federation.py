#!/usr/bin/env python3
"""Checks of the Active Directory federation of the realm (YAW-32, HU-32), against a running Keycloak and Samba AD.

Usage:  python3 -I deploy/keycloak/test_federation.py check        (make ad-federation-check)
        python3 -I deploy/keycloak/test_federation.py token <user>  (make token USER=yw.recruit01)
Needs: `make up PROFILE=ad`, `make ad-seed`, `make ad-cert`, the realm imported with KC_AD_ENABLED=true.
Env:   same as test_realm.py (KC_URL, KC_REALM, KC_WEB_URL, KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD).
`check` disables yw.hospital02 in AD for a moment (samba-tool in the container) and always enables it again.
`token` signs in with the browser flow (Authorization Code + PKCE S256, like the web app) and prints the DECODED
claims; the raw token and the password are never printed. Passwords come from deploy/ad/.secrets/passwords.env.
Exit code 0 when every check passes.
"""
import importlib.util
import json
import pathlib
import subprocess
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[2]
# Reuse the helpers (HTTP, PKCE, admin API) of the realm checks; that file only runs its checks as a script.
_spec = importlib.util.spec_from_file_location("test_realm", ROOT / "deploy/keycloak/test_realm.py")
tr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tr)

PROVIDER = "yawar-ad"
STAFF_ROLES = ["recruitment", "logistics", "hospital", "administrator", "auditor"]
DISABLE_USER = "yw.hospital02"
TOKEN_URL = f"{tr.KC}/realms/{tr.REALM}/protocol/openid-connect/token"


def staff():
    """users.csv: user -> (role, site). The group name is the Yawar role in upper case."""
    out = {}
    for line in (ROOT / "deploy/ad/users.csv").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            user, group, _given, _sn, site = line.split(";")
            out[user] = (group.lower(), site)
    return out


def password(user):
    key = user.replace(".", "_").upper() + "="
    for line in (ROOT / "deploy/ad/.secrets/passwords.env").read_text().splitlines():
        if line.startswith(key):
            return line.split("=", 1)[1]
    sys.exit(f"no password for {user} in deploy/ad/.secrets/passwords.env (run make ad-seed)")


def sign_in(user, pw):
    """Browser flow with PKCE. Returns (claims or None, error text of the login page)."""
    follow, no_follow = tr.browser()
    verifier, challenge = tr.pkce_pair()
    _, _, page = tr.request(tr.auth_url(challenge), opener=follow)
    action = tr.form_action(page)
    if not action:
        return None, "no login form"
    status, headers, body = tr.request(action, {"username": user, "password": pw}, opener=no_follow)
    location = headers.get("Location", "")
    code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get("code", [None])[0]
    if status != 302 or not code:
        return None, tr.html.unescape(tr.re.sub(r"\s+", " ", tr.re.sub(r"<[^>]+>", " ", body))).strip()[:160]
    status, _, body = tr.request(TOKEN_URL, {
        "grant_type": "authorization_code", "client_id": tr.CLIENT, "code": code,
        "redirect_uri": tr.REDIRECT, "code_verifier": verifier})
    if status != 200:
        return None, f"token endpoint {status}"
    return tr.jwt_claims(json.loads(body)["access_token"]), ""


def samba(*args):
    cmd = ["docker", "compose", "--env-file", str(ROOT / "deploy/local/.env"), "-f",
           str(ROOT / "deploy/local/docker-compose.yml"), "exec", "-T", "samba-ad", "samba-tool", *args]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=60, cwd=ROOT).returncode == 0


def staff_roles(claims):
    return sorted(set(claims["realm_access"]["roles"]) & set(STAFF_ROLES))


def local_user(token, username):
    users = tr.admin(f"/users?username={urllib.parse.quote(username)}&exact=true", token)[2]
    return users[0] if users else None


def provider_checks(token):
    _, _, comps = tr.admin("/components?type=org.keycloak.storage.UserStorageProvider", token)
    ad = next((c for c in comps if c["name"] == PROVIDER), None)
    check = tr.check
    check("federation provider yawar-ad present", ad is not None)
    if not ad:
        return None
    cfg = {k: (v[0] if v else "") for k, v in ad["config"].items()}
    check("provider is LDAP vendor ad, READ_ONLY, import on, no registration sync",
          cfg.get("vendor") == "ad" and cfg.get("editMode") == "READ_ONLY" and cfg.get("importEnabled") == "true"
          and cfg.get("syncRegistrations") == "false", cfg.get("editMode"))
    check("provider enabled (KC_AD_ENABLED=true)", cfg.get("enabled") == "true", cfg.get("enabled"))
    check("LDAPS with the Keycloak truststore", cfg.get("connectionUrl", "").startswith("ldaps://")
          and cfg.get("useTruststoreSpi") == "always", cfg.get("connectionUrl"))
    check("bind secret not exposed by the admin API", cfg.get("bindCredential") in ("**********", ""))
    _, _, mappers = tr.admin(f"/components?parent={ad['id']}", token)
    names = {m["name"]: m for m in mappers}
    check("mappers: username, names, email, site, MSAD account controls, staff roles",
          {"username", "first name", "last name", "email", "site", "MSAD account controls", "staff roles"} <= set(names),
          sorted(names))
    site = (names.get("site") or {}).get("config", {})
    check("site mapper reads physicalDeliveryOfficeName, read-only",
          site.get("ldap.attribute") == ["physicalDeliveryOfficeName"] and site.get("read.only") == ["true"])
    roles = (names.get("staff roles") or {}).get("config", {})
    check("role mapper reads GG-YAW-* groups under OU=Grupos, read-only",
          roles.get("roles.ldap.filter") == ["(cn=GG-YAW-*)"] and roles.get("mode") == ["READ_ONLY"]
          and roles.get("roles.dn", [""])[0].startswith("OU=Grupos"))
    return ad


def sync_check(token, ad):
    status, _, res = tr.admin(f"/user-storage/{ad['id']}/sync?action=triggerFullSync", token, "POST")
    users = staff()
    imported = 0
    for user in users:
        local = local_user(token, user)
        # The list endpoint omits federationLink; the single-user representation has it.
        full = tr.admin(f"/users/{local['id']}", token)[2] if local else {}
        imported += full.get("federationLink") == ad["id"]
    synced = (res.get("added", 0) + res.get("updated", 0)) if isinstance(res, dict) else 0
    tr.check("full sync imports the 10 staff users", status == 200 and res.get("failed") == 0 and synced == len(users) == imported == 10,
             f"status={status} result={res} federated={imported}")


def role_checks(token):
    rows = staff()
    ok = no_consent = 0
    for user, (role, site) in rows.items():
        claims, err = sign_in(user, password(user))
        no_consent += claims is not None and "privacy_consent" not in claims
        good = claims is not None and staff_roles(claims) == [role] and claims.get("site") == site
        ok += good
        if not good:
            print(f"      {user}: roles={staff_roles(claims) if claims else None} site={claims.get('site') if claims else None} {err}")
    tr.check(f"each staff user gets exactly its role and site ({ok}/{len(rows)})", ok == len(rows))

    claims, _ = sign_in("yw.logistics01", password("yw.logistics01"))
    roles = set(claims["realm_access"]["roles"]) if claims else set()
    tr.check("hu32_sc1_logistics_user_has_logistics_role_and_site",
             claims is not None and "logistics" in roles and "administrator" not in roles
             and claims.get("site") == "quito-hcam" and "yawar-api" in (claims.get("aud") if isinstance(claims.get("aud"), list)
                                                                        else [claims.get("aud")]),
             f"roles={sorted(roles)} site={claims.get('site') if claims else None}")
    tr.check("tokens never carry the AD group roles (GG-YAW-*)",
             claims is not None and not any(r.startswith("GG-YAW-") for r in roles), sorted(roles))
    tr.check(f"staff_token_has_no_privacy_consent_claim ({no_consent}/{len(rows)})", no_consent == len(rows))


def disabled_check():
    pw = password(DISABLE_USER)
    before = during = after = None
    disabled = reenabled = False
    err_after = ""
    try:
        samba("user", "enable", DISABLE_USER)
        before, _ = sign_in(DISABLE_USER, pw)
        disabled = samba("user", "disable", DISABLE_USER)
        during = sign_in(DISABLE_USER, pw)[0] if disabled else object()
    finally:
        reenabled = samba("user", "enable", DISABLE_USER)
        if not reenabled:
            print(f"\n!!! WARNING: could not re-enable {DISABLE_USER} in AD. Run manually:\n"
                  f"!!!   docker compose --env-file deploy/local/.env -f deploy/local/docker-compose.yml "
                  f"exec samba-ad samba-tool user enable {DISABLE_USER}\n")
    after, err_after = sign_in(DISABLE_USER, pw)
    tr.check("disabled AD account cannot sign in, and can again once re-enabled",
             before is not None and disabled and during is None and reenabled and after is not None,
             f"before={before is not None} disabled={disabled} during={'blocked' if during is None else 'LOGGED IN'} "
             f"reenabled={reenabled} after={after is not None} {err_after}")


def read_only_check(token):
    user = local_user(token, "yw.recruit01")
    uid = user["id"]
    status, _, _ = tr.admin(f"/users/{uid}/reset-password", token, "PUT",
                            {"type": "password", "value": "Cambio-" + tr.secrets.token_hex(6), "temporary": False})
    tr.check("password cannot be changed in Keycloak (READ_ONLY)", status >= 400, status)
    claims, _ = sign_in("yw.recruit01", password("yw.recruit01"))
    tr.check("the AD password still works after the rejected change", claims is not None)
    status, _, _ = tr.admin(f"/users/{uid}", token, "PUT", {**user, "lastName": "Changed"})
    tr.check("profile data cannot be written back to AD", status >= 400, status)
    _, _, after = tr.admin(f"/users/{uid}", token)
    tr.check("last name still comes from AD", after.get("lastName") == "One", after.get("lastName"))


def run_checks():
    print(f"Keycloak {tr.KC} realm {tr.REALM}: AD federation")
    token = tr.admin_token()
    ad = provider_checks(token)
    if ad:
        sync_check(token, ad)
        role_checks(token)
        disabled_check()
        read_only_check(tr.admin_token())
    failed = [n for n, ok in tr.results if not ok]
    print(f"\n{len(tr.results) - len(failed)} passed, {len(failed)} failed")
    return 1 if failed else 0


def show_token(user):
    if user not in staff():
        sys.exit(f"unknown user {user!r}; users: " + ", ".join(staff()))
    claims, err = sign_in(user, password(user))
    if claims is None:
        sys.exit(f"sign-in failed for {user}: {err}")
    shown = {k: claims.get(k) for k in ("iss", "azp", "aud", "sub", "preferred_username", "email", "site", "idp")}
    shown["realm_roles"] = claims["realm_access"]["roles"]
    shown["expires_in_s"] = claims["exp"] - claims["iat"]
    print(json.dumps({k: v for k, v in shown.items() if v is not None}, indent=2))


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "check":
        sys.exit(run_checks())
    if len(sys.argv) == 3 and sys.argv[1] == "token":
        show_token(sys.argv[2])
    else:
        sys.exit(__doc__)
