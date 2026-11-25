#!/usr/bin/env python3
"""Integration and functional checks of the Keycloak realm as code (YAW-31), against a running Keycloak.

Usage:  python3 -I deploy/keycloak/test_realm.py            (make keycloak-check)
Env:    KC_URL (http://localhost:8080), KC_REALM (yawar-dev), KC_WEB_URL (http://localhost:5173),
        KEYCLOAK_ADMIN_USER / KEYCLOAK_ADMIN_PASSWORD (master admin), MAILPIT_URL (http://localhost:8025)
Only fictional test users are created (…@example.test) and they are deleted at the end.
Exit code 0 when every check passes.
"""
import base64
import hashlib
import html
import http.cookiejar
import json
import os
import re
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

KC = os.environ.get("KC_URL", "http://localhost:8080").rstrip("/")
REALM = os.environ.get("KC_REALM", "yawar-dev")
WEB = os.environ.get("KC_WEB_URL", "http://localhost:5173").rstrip("/")
MAILPIT = os.environ.get("MAILPIT_URL", "http://localhost:8025").rstrip("/")
ADMIN_USER = os.environ["KEYCLOAK_ADMIN_USER"]
ADMIN_PASSWORD = os.environ["KEYCLOAK_ADMIN_PASSWORD"]
CLIENT = "yawar-web"
REDIRECT = WEB + "/callback"
ROLES = ["donor", "recruitment", "logistics", "hospital", "administrator", "auditor", "agent"]

results = []


def check(name, condition, detail=""):
    results.append((name, bool(condition)))
    print(("PASS  " if condition else "FAIL  ") + name + (f"  ({detail})" if detail and not condition else ""))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request(url, data=None, headers=None, method=None, opener=None):
    body = None
    if data is not None:
        body = data if isinstance(data, bytes) else urllib.parse.urlencode(data, doseq=True).encode()
    req = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    try:
        with (opener or urllib.request.build_opener()).open(req, timeout=15) as res:
            return res.status, dict(res.headers), res.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()


def admin_token():
    status, _, body = request(f"{KC}/realms/master/protocol/openid-connect/token", {
        "grant_type": "password", "client_id": "admin-cli", "username": ADMIN_USER, "password": ADMIN_PASSWORD})
    assert status == 200, f"admin login failed: {status}"
    return json.loads(body)["access_token"]


def admin(path, token, method="GET", payload=None):
    headers = {"Authorization": f"Bearer {token}"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode()
    status, res_headers, body = request(f"{KC}/admin/realms/{REALM}{path}", data, headers, method)
    return status, res_headers, (json.loads(body) if body.strip().startswith(("{", "[")) else body)


def jwt_claims(token):
    part = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


class LocalhostCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    """Keycloak marks its cookies Secure; browsers still send them to http://localhost, so do the same there only."""

    def return_ok_secure(self, cookie, request):
        host = urllib.parse.urlparse(request.get_full_url()).hostname
        return host in ("localhost", "127.0.0.1") or super().return_ok_secure(cookie, request)


def browser():
    jar = http.cookiejar.CookieJar(policy=LocalhostCookiePolicy())
    follow = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    no_follow = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect)
    return follow, no_follow


def pkce_pair():
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


def auth_url(challenge=None):
    params = {"client_id": CLIENT, "response_type": "code", "redirect_uri": REDIRECT, "scope": "openid",
              "state": secrets.token_hex(8), "ui_locales": "es"}
    if challenge:
        params.update(code_challenge=challenge, code_challenge_method="S256")
    return f"{KC}/realms/{REALM}/protocol/openid-connect/auth?" + urllib.parse.urlencode(params)


def form_action(page):
    m = re.search(r'<form[^>]+action="([^"]+)"', page)
    return html.unescape(m.group(1)) if m else None


# --------------------------------------------------------------------------------------------------------
def configuration_checks(token):
    status, _, realm = admin("", token)
    check("realm exists", status == 200, status)
    check("self-registration enabled, email as username", realm.get("registrationAllowed")
          and realm.get("registrationEmailAsUsername"))
    check("email verification and password reset enabled", realm.get("verifyEmail") and realm.get("resetPasswordAllowed"))
    check("brute-force protection on", realm.get("bruteForceProtected"))
    check("password policy min length 10", "length(10)" in (realm.get("passwordPolicy") or ""))
    check("Spanish default locale", realm.get("defaultLocale") == "es")
    check("access token lifespan 5 min", realm.get("accessTokenLifespan") == 300)
    check("SMTP configured", bool((realm.get("smtpServer") or {}).get("host")))

    _, _, roles = admin("/roles", token)
    names = {r["name"] for r in roles}
    check("realm roles " + ", ".join(ROLES), set(ROLES) <= names, sorted(names))
    _, _, composites = admin(f"/roles/default-roles-{REALM}/composites/realm", token)
    check("new users get the donor role", "donor" in {r["name"] for r in composites})
    check("new users get no staff role", not ({r["name"] for r in composites} & (set(ROLES) - {"donor"})))

    _, _, clients = admin(f"/clients?clientId={CLIENT}", token)
    c = clients[0] if clients else {}
    check("yawar-web is a public client", c.get("publicClient") is True)
    check("only Authorization Code flow (no implicit, no password grant)", c.get("standardFlowEnabled")
          and not c.get("implicitFlowEnabled") and not c.get("directAccessGrantsEnabled"))
    check("PKCE S256 required", (c.get("attributes") or {}).get("pkce.code.challenge.method") == "S256")
    check("only exact redirect URIs (callback, silent SSO)", sorted(c.get("redirectUris") or [])
          == sorted([WEB + "/callback", WEB + "/silent-check-sso.html"]), c.get("redirectUris"))
    check("no offline tokens for the browser client", "offline_access" not in (c.get("optionalClientScopes") or [])
          and "offline_access" not in (c.get("defaultClientScopes") or []))

    status, _, idp = admin("/identity-provider/instances/google", token)
    check("Google identity provider defined", status == 200 and idp.get("providerId") == "google")
    check("Google client secret not exposed by the admin API",
          (idp.get("config") or {}).get("clientSecret") in ("**********", None, "not-configured"))

    _, _, profile = admin("/users/profile", token)
    consent = next((a for a in profile.get("attributes", []) if a["name"] == "privacy_consent"), None)
    check("privacy_consent attribute required for users", consent is not None
          and "user" in ((consent.get("required") or {}).get("roles") or []))


def pkce_login_checks(token):
    email = f"pkce-{secrets.token_hex(4)}@example.test"
    password = "Prueba-" + secrets.token_hex(6)
    status, headers, _ = admin("/users", token, "POST", {
        "username": email, "email": email, "firstName": "Ana", "lastName": "Prueba", "enabled": True,
        "emailVerified": True, "attributes": {"privacy_consent": ["accepted"]},
        "credentials": [{"type": "password", "value": password, "temporary": False}]})
    check("admin can create a fictional donor", status == 201, status)
    user_id = headers.get("Location", "").rsplit("/", 1)[-1]
    try:
        follow, no_follow = browser()
        status, headers, _ = request(auth_url(), opener=no_follow)
        loc = headers.get("Location", "")
        check("authorization request without PKCE is rejected", status == 302 and "error=" in loc
              and "code=" not in loc, loc[:100])

        verifier, challenge = pkce_pair()
        _, _, page = request(auth_url(challenge), opener=follow)
        check("login page served in Spanish", 'lang="es"' in page)
        status, headers, _ = request(form_action(page), {"username": email, "password": password}, opener=no_follow)
        location = headers.get("Location", "")
        code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query).get("code", [None])[0]
        check("login redirects back to the web app with a code", status == 302 and location.startswith(REDIRECT)
              and code, location[:80])

        status, _, body = request(f"{KC}/realms/{REALM}/protocol/openid-connect/token", {
            "grant_type": "authorization_code", "client_id": CLIENT, "code": code, "redirect_uri": REDIRECT})
        check("code without code_verifier is rejected", status == 400, status)

        # Second authorization with the existing session: Keycloak answers straight away with a new code.
        verifier, challenge = pkce_pair()
        status, headers, _ = request(auth_url(challenge), opener=no_follow)
        code = urllib.parse.parse_qs(urllib.parse.urlparse(headers.get("Location", "")).query).get("code", [None])[0]
        status, _, body = request(f"{KC}/realms/{REALM}/protocol/openid-connect/token", {
            "grant_type": "authorization_code", "client_id": CLIENT, "code": code, "redirect_uri": REDIRECT,
            "code_verifier": verifier})
        check("code + code_verifier returns tokens", status == 200, body[:120])
        claims = jwt_claims(json.loads(body)["access_token"]) if status == 200 else {}
        check("access token carries the donor role", "donor" in (claims.get("realm_access") or {}).get("roles", []))
        aud = claims.get("aud")
        check("access token audience includes yawar-api", "yawar-api" in (aud if isinstance(aud, list) else [aud]))
        check("access token issuer is the realm", claims.get("iss") == f"{KC}/realms/{REALM}", claims.get("iss"))
        check("access token has sub and verified email", bool(claims.get("sub")) and claims.get("email") == email
              and claims.get("email_verified") is True)
        check("access token carries the privacy consent", claims.get("privacy_consent") == ["accepted"],
              claims.get("privacy_consent"))
        roles = set((claims.get("realm_access") or {}).get("roles", []))
        check("donor token has no staff, agent or offline roles",
              not roles & {"recruitment", "logistics", "hospital", "administrator", "auditor", "agent", "offline_access"},
              sorted(roles))

        status, headers, _ = request(auth_url(challenge).replace(urllib.parse.quote(REDIRECT, safe=""),
                                     urllib.parse.quote(WEB + "/evil", safe="")), opener=no_follow)
        check("unregistered redirect_uri is refused", status == 400 or "evil" not in headers.get("Location", ""))

        status, _, _ = request(f"{KC}/realms/{REALM}/protocol/openid-connect/token", {
            "grant_type": "password", "client_id": CLIENT, "username": email, "password": password})
        check("password grant is disabled for yawar-web", status in (400, 401), status)
    finally:
        if user_id:
            admin(f"/users/{user_id}", token, "DELETE")


def registration_checks(token):
    email = f"signup-{secrets.token_hex(4)}@example.test"
    try:
        _registration_checks(token, email)
    finally:
        _, _, users = admin(f"/users?email={urllib.parse.quote(email)}&exact=true", token)
        for u in users if isinstance(users, list) else []:
            admin(f"/users/{u['id']}", token, "DELETE")
        request(f"{MAILPIT}/api/v1/search?query=" + urllib.parse.quote(f"to:{email}"), method="DELETE")


def _registration_checks(token, email):
    password = "Prueba-" + secrets.token_hex(6)
    follow, _ = browser()
    _, challenge = pkce_pair()
    _, _, login = request(auth_url(challenge), opener=follow)
    m = re.search(r'href="([^"]*login-actions/registration[^"]*)"', login)
    check("login page links to self-registration", m is not None)
    if not m:
        return
    _, _, form = request(urllib.parse.urljoin(KC, html.unescape(m.group(1))), opener=follow)
    check("registration form asks for the privacy consent", "privacy_consent" in form)

    action = form_action(form)
    check("registration form has an action", action is not None)
    if not action:
        return
    # Names are optional (data minimization): HU-01 asks for email, password and consent only.
    fields = {"email": email, "password": password, "password-confirm": password}
    _, _, page = request(action, fields, opener=follow)
    check("registration without consent is refused", "privacy_consent" in page and "verify" not in page.lower()
          and "verific" not in page.lower(), page[:120])

    action = form_action(page)
    if not action:
        check("registration form shown again after the refusal", False)
        return
    _, _, page = request(action, dict(fields, privacy_consent="accepted"), opener=follow)
    check("registration with consent succeeds and asks to verify the email",
          "verif" in page.lower(), page[:160])

    found = False
    for _ in range(10):
        _, _, body = request(f"{MAILPIT}/api/v1/search?query=" + urllib.parse.quote(f"to:{email}"))
        if body and json.loads(body).get("messages_count", 0) > 0:
            found = True
            break
        time.sleep(1)
    check("verification email delivered to Mailpit", found)

    _, _, users = admin(f"/users?email={urllib.parse.quote(email)}&exact=true", token)
    if users:
        u = users[0]
        check("registered user stored the consent", (u.get("attributes") or {}).get("privacy_consent") == ["accepted"])
        _, _, roles = admin(f"/users/{u['id']}/role-mappings/realm/composite", token)
        check("registered user is a donor", "donor" in {r["name"] for r in roles})


if __name__ == "__main__":
    print(f"Keycloak {KC} realm {REALM}")
    tok = admin_token()
    configuration_checks(tok)
    pkce_login_checks(tok)
    registration_checks(admin_token())
    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed")
    sys.exit(1 if failed else 0)
