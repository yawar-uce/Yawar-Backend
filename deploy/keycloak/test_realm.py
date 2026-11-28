#!/usr/bin/env python3
"""Integration and functional checks of the Keycloak realm as code (YAW-31), against a running Keycloak.

Usage:  python3 -I deploy/keycloak/test_realm.py            (make keycloak-check)
HU-02 (Google) is tested against a mock OIDC provider: start it with `make up PROFILE=idp-mock`; without it
those checks are skipped. The test IdP `google-test` is created through the admin API and deleted afterwards.
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
# OIDC stub standing in for Google (compose profile idp-mock): reached by the browser at IDP_MOCK_URL and by
# Keycloak (inside the compose network) at IDP_MOCK_INTERNAL.
IDP_MOCK = os.environ.get("IDP_MOCK_URL", "http://localhost:" + os.environ.get("IDP_MOCK_PORT", "8099")).rstrip("/")
IDP_MOCK_INTERNAL = os.environ.get("IDP_MOCK_INTERNAL_URL", "http://idp-mock:8080").rstrip("/")
TEST_IDP = "google-test"
ADMIN_USER = os.environ["KEYCLOAK_ADMIN_USER"]
ADMIN_PASSWORD = os.environ["KEYCLOAK_ADMIN_PASSWORD"]
CLIENT = "yawar-web"
REDIRECT = WEB + "/callback"
DESKTOP_CLIENT = "yawar-desktop"
DESKTOP = "http://localhost:5180"  # fixed loopback port of the desktop app, the same in every realm
ROLES = ["donor", "recruitment", "logistics", "hospital", "administrator", "auditor", "agent"]

CONSENT_REQUIRED = "Necesitamos tu consentimiento para tratar tus datos (LOPDP) y crear tu cuenta."
DUPLICATE_EMAIL = "Este correo ya está registrado. Inicia sesión o recupera tu contraseña."

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

    desktop_client_checks(token, c)

    status, _, idp = admin("/identity-provider/instances/google", token)
    check("Google identity provider defined", status == 200 and idp.get("providerId") == "google")
    check("Google client secret not exposed by the admin API",
          (idp.get("config") or {}).get("clientSecret") in ("**********", None, "not-configured"))

    _, _, profile = admin("/users/profile", token)
    consent = next((a for a in profile.get("attributes", []) if a["name"] == "privacy_consent"), None)
    check("privacy_consent attribute required for users", consent is not None
          and "user" in ((consent.get("required") or {}).get("roles") or []))


def mapper_settings(client):
    # Keycloak adds introspection.token.claim on its own to some mappers; the realm files do not set it.
    return sorted((m["name"], m["protocolMapper"],
                   sorted((k, v) for k, v in m["config"].items() if k != "introspection.token.claim"))
                  for m in client.get("protocolMappers") or [])


def scope_mapped_roles(token, client):
    _, _, roles = admin(f"/clients/{client.get('id')}/scope-mappings/realm", token)
    return sorted(r["name"] for r in roles) if isinstance(roles, list) else None


def desktop_client_checks(token, web):
    """yawar-desktop (YAW-220) is yawar-web on the loopback port of the desktop app."""
    _, _, clients = admin(f"/clients?clientId={DESKTOP_CLIENT}", token)
    c = clients[0] if clients else {}
    check("yawar-desktop is a public client", c.get("publicClient") is True)
    check("yawar-desktop: only Authorization Code flow", c.get("standardFlowEnabled")
          and not c.get("implicitFlowEnabled") and not c.get("directAccessGrantsEnabled"))
    check("yawar-desktop: PKCE S256 required", (c.get("attributes") or {}).get("pkce.code.challenge.method") == "S256")
    check("yawar-desktop: only the loopback redirect URIs", sorted(c.get("redirectUris") or [])
          == sorted([DESKTOP + "/callback", DESKTOP + "/silent-check-sso.html"]), c.get("redirectUris"))
    check("yawar-desktop: only the loopback web origin", c.get("webOrigins") == [DESKTOP], c.get("webOrigins"))
    check("yawar-desktop: same default and optional scopes as yawar-web",
          sorted(c.get("defaultClientScopes") or []) == sorted(web.get("defaultClientScopes") or [])
          and sorted(c.get("optionalClientScopes") or []) == sorted(web.get("optionalClientScopes") or []))
    check("yawar-desktop: same token mappers as yawar-web", bool(c) and mapper_settings(c) == mapper_settings(web))
    check("yawar-desktop: same role scope mappings as yawar-web", bool(c) and c.get("fullScopeAllowed") is False
          and scope_mapped_roles(token, c) == scope_mapped_roles(token, web))

    verifier, challenge = pkce_pair()
    url = auth_url(challenge).replace("client_id=" + CLIENT, "client_id=" + DESKTOP_CLIENT).replace(
        urllib.parse.quote(REDIRECT, safe=""), urllib.parse.quote(DESKTOP + "/callback", safe=""))
    status, _, page = request(url)
    check("yawar-desktop: authorization request shows the login page", status == 200 and form_action(page), status)


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
    _, _, none = admin(f"/users?email={urllib.parse.quote(email)}&exact=true", token)
    check("hu01_sc3_without_consent_no_account_and_reason",
          not none and CONSENT_REQUIRED in html.unescape(page), " ".join(re.findall(r"(?:Necesit|Este correo|obligatorio|Please|exist|registrad)[^<]{0,120}", html.unescape(page))))

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
        check("hu01_sc1_account_created_with_donor_role_and_verification_email",
              "donor" in {r["name"] for r in roles} and found and not u.get("emailVerified"))

        # Scenario 2: the same email again (another browser session) suggests sign-in or password recovery.
        again, _ = browser()
        _, _, login = request(auth_url(pkce_pair()[1]), opener=again)
        m = re.search(r'href="([^"]*login-actions/registration[^"]*)"', login)
        _, _, form = request(urllib.parse.urljoin(KC, html.unescape(m.group(1))), opener=again)
        _, _, page = request(form_action(form), {"email": email, "password": password, "password-confirm": password,
                                                  "privacy_consent": "accepted"}, opener=again)
        _, _, same = admin(f"/users?email={urllib.parse.quote(email)}&exact=true", token)
        check("hu01_sc2_duplicate_email_suggests_signin_or_recovery",
              DUPLICATE_EMAIL in html.unescape(page) and len(same) == 1, " ".join(re.findall(r"(?:Necesit|Este correo|obligatorio|Please|exist|registrad)[^<]{0,120}", html.unescape(page))))


# --- HU-02: sign up with Google (the realm's first broker login flow, tested with an OIDC stub) -------------
def mock_available():
    try:
        status, _, _ = request(f"{IDP_MOCK}/default/.well-known/openid-configuration")
        return status == 200
    except OSError:
        return False


def create_test_idp(token):
    """OIDC IdP like `google` (same flow, trustEmail) but pointing to the stub. Removed by the caller."""
    admin(f"/identity-provider/instances/{TEST_IDP}", token, "DELETE")
    status, _, body = admin("/identity-provider/instances", token, "POST", {
        "alias": TEST_IDP, "providerId": "oidc", "enabled": True, "trustEmail": True, "storeToken": False,
        "firstBrokerLoginFlowAlias": "yawar-first-broker-login",
        "config": {"clientId": "yawar", "clientSecret": secrets.token_hex(8), "clientAuthMethod": "client_secret_post",
                   "authorizationUrl": f"{IDP_MOCK}/default/authorize", "tokenUrl": f"{IDP_MOCK_INTERNAL}/default/token",
                   "jwksUrl": f"{IDP_MOCK_INTERNAL}/default/jwks", "issuer": f"{IDP_MOCK_INTERNAL}/default",
                   "validateSignature": "true", "useJwksUrl": "true", "disableUserInfo": "true",
                   "defaultScope": "openid email profile", "syncMode": "IMPORT", "pkceEnabled": "false"}})
    return status == 201


def walk(opener, url, data=None):
    """Follow redirects by hand until the web app callback (returned as `code`) or an HTML page."""
    for _ in range(15):
        status, headers, body = request(url, data, opener=opener)
        data = None
        loc = headers.get("Location") or headers.get("location")
        if status in (301, 302, 303) and loc:
            url = urllib.parse.urljoin(url, loc)
            if url.startswith(REDIRECT):
                return url, None, urllib.parse.parse_qs(urllib.parse.urlparse(url).query).get("code", [None])[0]
            continue
        return url, body, None
    return url, "", None


def form_values(page):
    """Prefilled text inputs of the first form of a Keycloak page (what a browser would submit)."""
    values = {}
    for tag in re.findall(r"<input[^>]*>", page or ""):
        name = re.search(r'name="([^"]+)"', tag)
        kind = re.search(r'type="([^"]+)"', tag)
        value = re.search(r'value="([^"]*)"', tag)
        if name and (kind.group(1) if kind else "text") in ("text", "email") and name.group(1) != "cancel":
            values[name.group(1)] = html.unescape(value.group(1)) if value else ""
    return values


def google_login(claims, subject, pending=None):
    """Browser-less login with the stub IdP. Returns (browser, page url, html, code) of where the flow stopped."""
    follow, no_follow = browser()
    verifier, challenge = pkce_pair()
    url, page, code = walk(no_follow, auth_url(challenge) + "&kc_idp_hint=" + TEST_IDP)
    if page is not None and "claims" in page:  # stub login form: the typed claims become the id token
        url, page, code = walk(no_follow, url, {"username": subject, "claims": json.dumps(claims)})
    return no_follow, url, page, code, verifier


def token_for(code, verifier):
    status, _, body = request(f"{KC}/realms/{REALM}/protocol/openid-connect/token", {
        "grant_type": "authorization_code", "client_id": CLIENT, "code": code, "redirect_uri": REDIRECT,
        "code_verifier": verifier})
    return jwt_claims(json.loads(body)["access_token"]) if status == 200 else {}


def users_by_email(token, email):
    _, _, users = admin(f"/users?email={urllib.parse.quote(email)}&exact=true", token)
    return users if isinstance(users, list) else []


def google_checks(token):
    if not mock_available():
        print(f"SKIP  HU-02 Google checks: OIDC stub not running at {IDP_MOCK} (make up PROFILE=idp-mock)")
        return
    # Local dev only: the test IdP is created in the realm and always deleted in `finally`.
    emails = [f"google-{secrets.token_hex(4)}@example.test" for _ in range(3)]
    try:
        ok = create_test_idp(token)
        check("test OIDC identity provider google-test created", ok)
        if ok:
            _google_checks(token, *emails)
    finally:
        admin(f"/identity-provider/instances/{TEST_IDP}", token, "DELETE")
        for e in emails:
            for u in users_by_email(token, e):
                admin(f"/users/{u['id']}", token, "DELETE")


def _google_checks(token, new_email, verified_email, unverified_email):
    # Scenario 1: a new Google user is created from the Google claims; only the privacy consent is asked.
    claims = {"email": new_email, "email_verified": True, "given_name": "Gabriela", "family_name": "Quishpe"}
    nf, url, page, code, verifier = google_login(claims, "sub-" + secrets.token_hex(4))
    fields = form_values(page)
    # "Review profile" shows the profile prefilled from Google; the only thing the user has to do is tick the consent.
    check("hu02_sc1_review_profile_prefilled_from_google_and_asks_only_the_consent",
          fields.get("email") == new_email and fields.get("firstName") == "Gabriela"
          and fields.get("lastName") == "Quishpe" and "privacy_consent" in (page or "")
          and set(fields) <= {"email", "firstName", "lastName", "username"}, fields)
    if page is not None and "privacy_consent" in page:
        url, page, code = walk(nf, urllib.parse.urljoin(url, form_action(page)), dict(fields, privacy_consent="accepted"))
    users = users_by_email(token, new_email)
    u = users[0] if users else {}
    _, _, roles = admin(f"/users/{u['id']}/role-mappings/realm/composite", token) if u else (0, 0, [])
    check("hu02_sc1_google_creates_account_without_extra_form", bool(code) and len(users) == 1
          and u.get("firstName") == "Gabriela" and u.get("lastName") == "Quishpe" and u.get("email") == new_email
          and u.get("emailVerified") is True and "donor" in {r["name"] for r in roles}
          and (u.get("attributes") or {}).get("privacy_consent") == ["accepted"], f"code={bool(code)} user={u}")
    at = token_for(code, verifier) if code else {}
    check("google login token carries idp, donor role and consent", at.get("idp") == TEST_IDP
          and "donor" in (at.get("realm_access") or {}).get("roles", []) and at.get("privacy_consent") == ["accepted"], at.get("idp"))

    # Scenario 2: the email already belongs to an account: it is linked (not duplicated) once its password is entered.
    pw = "Prueba-" + secrets.token_hex(6)
    for email, verified in ((verified_email, True), (unverified_email, False)):
        st, _, _ = admin("/users", token, "POST", {
            "username": email, "email": email, "enabled": True, "emailVerified": verified,
            "attributes": {"privacy_consent": ["accepted"]},
            "credentials": [{"type": "password", "value": pw, "temporary": False}]})
        check(f"pre-created {'verified' if verified else 'unverified'} local account", st == 201, st)

    def existing_login(email, password):
        claims = {"email": email, "email_verified": True, "given_name": "Luis", "family_name": "Tipan"}
        nf, url, page, code, verifier = google_login(claims, "sub-" + secrets.token_hex(4))
        if page is not None and "privacy_consent" in page:  # review profile: tick the consent, the rest is prefilled
            url, page, code = walk(nf, urllib.parse.urljoin(url, form_action(page)),
                                   dict(form_values(page), privacy_consent="accepted"))
        asks_password = page is not None and 'name="password"' in page
        before = bool(code)
        if asks_password:
            url, page, code = walk(nf, urllib.parse.urljoin(url, form_action(page)),
                                   {"username": email, "password": password})
        users = users_by_email(token, email)
        links = admin(f"/users/{users[0]['id']}/federated-identity", token)[2] if users else []
        return asks_password, before, code, users, [l.get("identityProvider") for l in links]

    asks, early, code, users, links = existing_login(verified_email, pw)
    check("hu02_sc2_google_links_existing_verified_account", asks and not early and bool(code) and len(users) == 1
          and links == [TEST_IDP], f"asks={asks} early={early} code={bool(code)} users={len(users)} links={links}")

    # Never linked automatically (pre-registration takeover): an unverified local account needs its password too,
    # and a wrong password links nothing.
    asks, early, code, users, links = existing_login(unverified_email, "Wrong-" + secrets.token_hex(6))
    check("hu02_sc2b_unverified_existing_account_is_not_auto_linked", asks and not early and not code
          and len(users) == 1 and not links, f"asks={asks} early={early} code={bool(code)} links={links}")


if __name__ == "__main__":
    print(f"Keycloak {KC} realm {REALM}")
    tok = admin_token()
    configuration_checks(tok)
    pkce_login_checks(tok)
    registration_checks(admin_token())
    google_checks(admin_token())
    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)} passed, {len(failed)} failed")
    sys.exit(1 if failed else 0)
