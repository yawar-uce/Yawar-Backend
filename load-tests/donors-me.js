// k6 load test of GET /api/v1/donors/me through the API Gateway (YAW-48, V-05, RNF-05).
//
//   PROFILE=smoke  20 VUs x 1 min          CI profile (job `load`), p95 < 3 s, errors < 1 %
//   PROFILE=load   100 VUs, 7 min          p95 <= 2 s, errors < 1 %   (manual)
//   PROFILE=stress 100 -> 300 VUs, 16 min  p95 < 3 s, errors < 1 %   (manual, finds the knee)
//
// Run it with `make load PROFILE=...` (docker, pinned image). See load-tests/README.md.
//
// Users and tokens: the gateway limits every user to 60 requests/minute, and that limit stays untouched. So each VU
// is its own donor (created in setup()) and paces itself at ~50 requests/minute (1.2 s think time). setup() creates
// a temporary direct-grant client `yawar-load` (scopes and mappers copied from `yawar-web`, same approach as the
// e2e-tests) and N donors through the Keycloak admin API; teardown() deletes them. teardown() also runs after
// failed thresholds and after Ctrl-C (SIGINT/SIGTERM); it does NOT run if k6 is killed (SIGKILL, runner lost) or
// if setup() itself fails (then setup() cleans up by itself). A leftover client or `load-donor-*@example.test`
// users are deleted by the next run's setup().
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { randomBytes } from 'k6/crypto';
import { markdown, text } from './lib/summary.js';

const PROFILE = __ENV.PROFILE || 'smoke';
const REALM = __ENV.REALM || 'yawar-dev';
const KEYCLOAK_URL = (__ENV.KEYCLOAK_URL || 'http://localhost:8080').replace(/\/$/, '');
const GATEWAY_URL = (__ENV.GATEWAY_URL || 'http://localhost:8000').replace(/\/$/, '');
const ADMIN_USER = __ENV.KEYCLOAK_ADMIN_USER;
const ADMIN_PASSWORD = __ENV.KEYCLOAK_ADMIN_PASSWORD;
const CLIENT_ID = 'yawar-load';
const USER_PREFIX = 'load-donor-';
const USER_DOMAIN = '@example.test';
const THINK_TIME_S = 1.2;           // ~50 requests/minute/user, under the gateway limit of 60
const TOKEN_MARGIN_MS = 60 * 1000;  // refresh a token one minute before it expires
const BATCH = 10;

const GATE = 'api:donors_me';
const PROFILES = {
  smoke: {
    users: 20,
    p95: 3000,
    scenarios: { smoke: { executor: 'constant-vus', vus: 20, duration: '1m' } },
  },
  load: {
    users: 100,
    p95: 2000,
    scenarios: {
      load: {
        executor: 'ramping-vus', startVUs: 0, gracefulRampDown: '10s',
        stages: [{ duration: '1m', target: 100 }, { duration: '5m', target: 100 }, { duration: '30s', target: 0 }],
      },
    },
  },
  stress: {
    users: 300,
    p95: 3000,
    scenarios: {
      stress: {
        executor: 'ramping-vus', startVUs: 0, gracefulRampDown: '10s',
        stages: [
          { duration: '2m', target: 100 }, { duration: '3m', target: 100 },
          { duration: '2m', target: 200 }, { duration: '3m', target: 200 },
          { duration: '2m', target: 300 }, { duration: '3m', target: 300 },
          { duration: '1m', target: 0 },
        ],
      },
    },
  },
};
const cfg = PROFILES[PROFILE];
if (!cfg) {
  throw new Error(`PROFILE '${PROFILE}' is unknown: use one of ${Object.keys(PROFILES).join(', ')}`);
}

export const options = {
  scenarios: cfg.scenarios,
  setupTimeout: '10m',
  teardownTimeout: '5m',
  // 304 is a success (If-None-Match after the first 200). 429 and 5xx count as errors.
  // Thresholds look only at the gateway calls (tag api:donors_me): the Keycloak admin calls of setup() and the
  // token refreshes must not count (a DELETE answering 404 is normal there).
  thresholds: {
    [`http_req_duration{expected_response:true,${GATE}}`]: [`p(95)<${cfg.p95}`],
    [`http_req_failed{${GATE}}`]: ['rate<0.01'],
    [`http_reqs{${GATE}}`]: ['count>0'],   // a run that sent nothing must not pass
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

http.setResponseCallback(http.expectedStatuses(200, 304));

const notModified = new Counter('donors_me_not_modified');
const rateLimited = new Counter('donors_me_rate_limited');
const refreshFailures = new Counter('token_refresh_failures');

// ---------------------------------------------------------------------------------------------------------------
// Safety guard (same rules as e2e-tests E2eConfig): the script creates and deletes clients and users with admin
// rights, so it only runs against a Yawar test realm and a Keycloak on this machine.
function guard() {
  if (REALM !== 'yawar-dev' && REALM !== 'yawar-qa') {
    throw new Error(`REALM '${REALM}' is not allowed: only yawar-dev, yawar-qa`);
  }
  const m = /^https?:\/\/([^/:@?#]+)(:\d+)?(\/|$)/i.exec(KEYCLOAK_URL);
  if (!m) throw new Error(`KEYCLOAK_URL '${KEYCLOAK_URL}' is not a valid http(s) URL`);
  const host = m[1].toLowerCase();
  if (host !== 'localhost' && host !== '127.0.0.1' && __ENV.LOAD_ALLOW_REMOTE_KEYCLOAK !== 'true') {
    throw new Error(`KEYCLOAK_URL host '${host}' is not local: set LOAD_ALLOW_REMOTE_KEYCLOAK=true to run with admin rights against it`);
  }
  if (!ADMIN_USER || !ADMIN_PASSWORD) {
    throw new Error('KEYCLOAK_ADMIN_USER and KEYCLOAK_ADMIN_PASSWORD are required (make load exports them from deploy/local/.env)');
  }
}

const KC = { tags: { api: 'keycloak' } };
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const rand = (n) => Array.from(new Uint8Array(randomBytes(n))).map((b) => (b + 256).toString(16).slice(1)).join('');

// Admin tokens live 60 s in the master realm: log in again when needed.
let admin = { token: null, until: 0 };
function adminToken() {
  if (admin.token && Date.now() < admin.until - 10000) return admin.token;
  const res = http.post(`${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token`, {
    grant_type: 'password', client_id: 'admin-cli', username: ADMIN_USER, password: ADMIN_PASSWORD,
  }, KC);
  if (res.status !== 200) {
    throw new Error(`Keycloak admin login failed (${res.status}) at ${KEYCLOAK_URL}: check KEYCLOAK_URL and KEYCLOAK_ADMIN_*`);
  }
  admin = { token: res.json('access_token'), until: Date.now() + res.json('expires_in') * 1000 };
  return admin.token;
}
function adminParams(extra) {
  return Object.assign({ headers: Object.assign({ Authorization: `Bearer ${adminToken()}` }, JSON_HEADERS) }, KC, extra);
}
const adminUrl = (path) => `${KEYCLOAK_URL}/admin/realms/${REALM}${path}`;
const idOf = (res, what) => {
  if (res.status !== 201) throw new Error(`Creating ${what} failed: ${res.status} ${res.body}`);
  const loc = res.headers.Location || res.headers.location;
  return loc.substring(loc.lastIndexOf('/') + 1);
};

function deleteStale() {
  const clients = http.get(adminUrl(`/clients?clientId=${CLIENT_ID}`), adminParams()).json();
  clients.forEach((c) => http.del(adminUrl(`/clients/${c.id}`), null, adminParams()));
  const users = http.get(adminUrl(`/users?search=${USER_PREFIX}&max=1000`), adminParams()).json();
  users
    .filter((u) => u.email && u.email.startsWith(USER_PREFIX) && u.email.endsWith(USER_DOMAIN))
    .forEach((u) => http.del(adminUrl(`/users/${u.id}`), null, adminParams()));
}

function createClient(secret) {
  const web = http.get(adminUrl('/clients?clientId=yawar-web'), adminParams()).json();
  if (web.length === 0) throw new Error(`Client yawar-web not found in realm ${REALM}: run make keycloak-import first`);
  const mappers = web[0].protocolMappers.map((m) => { const c = Object.assign({}, m); delete c.id; return c; });
  const client = {
    clientId: CLIENT_ID, name: 'Yawar load tests (temporary)', protocol: 'openid-connect', enabled: true,
    publicClient: false, secret, standardFlowEnabled: false, implicitFlowEnabled: false,
    directAccessGrantsEnabled: true, serviceAccountsEnabled: false, fullScopeAllowed: true,
    defaultClientScopes: web[0].defaultClientScopes, optionalClientScopes: [], protocolMappers: mappers,
  };
  return idOf(http.post(adminUrl('/clients'), JSON.stringify(client), adminParams()), `client ${CLIENT_ID}`);
}

function userBody(email, password) {
  return JSON.stringify({
    username: email, email, firstName: 'Load', lastName: 'Prueba', enabled: true, emailVerified: true,
    attributes: { privacy_consent: ['accepted'] },
    credentials: [{ type: 'password', value: password, temporary: false }],
  });
}

// `count` donors, created BATCH at a time; returns [{ id, email, password }].
function createUsers(count, created) {
  for (let from = 0; from < count; from += BATCH) {
    const specs = [];
    for (let i = from; i < Math.min(from + BATCH, count); i++) {
      specs.push({ email: `${USER_PREFIX}${rand(8)}${USER_DOMAIN}`, password: `L-${rand(12)}` });
    }
    const params = adminParams();
    const responses = http.batch(specs.map((s) => ['POST', adminUrl('/users'), userBody(s.email, s.password), params]));
    responses.forEach((r, i) => {
      specs[i].id = r.status === 201 ? idOf(r, 'user') : null;
      created.push(specs[i]);   // registered before the check below, so a half-created batch is still cleaned up
    });
    responses.forEach((r, i) => idOf(r, `user ${specs[i].email}`));
  }
}

function session(body) {
  return {
    access: body.access_token, refresh: body.refresh_token,
    expiresAt: Date.now() + body.expires_in * 1000,
  };
}

function loginAll(users, secret) {
  const sessions = [];
  for (let from = 0; from < users.length; from += BATCH) {
    const chunk = users.slice(from, from + BATCH);
    const responses = http.batch(chunk.map((u) => ['POST',
      `${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token`,
      { grant_type: 'password', client_id: CLIENT_ID, client_secret: secret, username: u.email, password: u.password },
      KC]));
    responses.forEach((r, i) => {
      if (r.status !== 200) throw new Error(`Token request for ${chunk[i].email} failed: ${r.status} ${r.body}`);
      sessions.push(session(r.json()));
    });
  }
  return sessions;
}

// The gateway answers 401 until it knows its routes, so readiness is proven with a real call (like E2eEnvironment):
// a throwaway donor calls /donors/me until ms-donors answers 200 (up to 150 s).
function awaitDonorsReachable(token) {
  const deadline = Date.now() + 150 * 1000;
  let last = -1;
  while (Date.now() < deadline) {
    const res = http.get(`${GATEWAY_URL}/api/v1/donors/me`, { headers: { Authorization: `Bearer ${token}` }, tags: { api: 'warmup' } });
    last = res.status;
    if (last === 200) return;
    sleep(2);
  }
  throw new Error(`GET ${GATEWAY_URL}/api/v1/donors/me never returned 200 in 150 s (last status ${last}): is ms-donors registered in Eureka and the realm imported?`);
}

function cleanup(ctx) {
  const failures = [];
  const attempt = (label, fn) => {
    try {
      const res = fn();
      if (res.status !== 204 && res.status !== 404) failures.push(`${label}: ${res.status}`);
    } catch (e) {
      failures.push(`${label}: ${e.message}`);
    }
  };
  // The client first: it is what must never stay behind.
  if (ctx.clientUuid) attempt('client', () => http.del(adminUrl(`/clients/${ctx.clientUuid}`), null, adminParams()));
  ctx.userIds.forEach((id) => attempt(`user ${id}`, () => http.del(adminUrl(`/users/${id}`), null, adminParams())));
  if (failures.length > 0) {
    console.error(`LOAD CLEANUP FAILED, delete client ${CLIENT_ID} and ${USER_PREFIX}* users in realm ${REALM} by hand: ${failures.slice(0, 10).join('; ')}`);
  }
}

export function setup() {
  guard();
  const ctx = { clientUuid: null, userIds: [] };
  const created = [];
  try {
    deleteStale();
    const secret = rand(16);
    ctx.clientUuid = createClient(secret);
    createUsers(cfg.users + 1, created);   // +1: the warm-up donor
    created.forEach((u) => u.id && ctx.userIds.push(u.id));
    const warm = created.pop();
    awaitDonorsReachable(loginAll([warm], secret)[0].access);
    const sessions = loginAll(created, secret);
    return { secret, clientUuid: ctx.clientUuid, userIds: ctx.userIds, sessions };
  } catch (e) {
    created.forEach((u) => u.id && ctx.userIds.indexOf(u.id) < 0 && ctx.userIds.push(u.id));
    cleanup(ctx);   // teardown() does not run when setup() fails
    throw e;
  }
}

export function teardown(data) {
  cleanup(data);
}

// ---------------------------------------------------------------------------------------------------------------
// Per VU state (every VU has its own JS runtime): its token and the ETag of its donor profile.
let current = null;
let etag = null;

function refreshIfNeeded(data) {
  if (current === null) {
    current = data.sessions[(__VU - 1) % data.sessions.length];
  }
  if (Date.now() < current.expiresAt - TOKEN_MARGIN_MS) return true;
  const res = http.post(`${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token`, {
    grant_type: 'refresh_token', client_id: CLIENT_ID, client_secret: data.secret, refresh_token: current.refresh,
  }, KC);
  if (res.status !== 200) {
    refreshFailures.add(1);
    return false;
  }
  current = session(res.json());
  return true;
}

export default function (data) {
  if (!refreshIfNeeded(data)) {
    sleep(THINK_TIME_S);
    return;
  }
  const headers = { Authorization: `Bearer ${current.access}` };
  if (etag) headers['If-None-Match'] = etag;
  const res = http.get(`${GATEWAY_URL}/api/v1/donors/me`, { headers, tags: { api: 'donors_me' } });
  if (res.status === 200) {
    etag = res.headers.Etag || res.headers.ETag || etag;
  } else if (res.status === 304) {
    notModified.add(1);
  } else if (res.status === 429) {
    rateLimited.add(1);
  }
  check(res, { 'status is 200 or 304': (r) => r.status === 200 || r.status === 304 });
  sleep(THINK_TIME_S);
}

export function handleSummary(data) {
  delete data.setup_data; // it holds the client secret and the donors' tokens: never into a report
  return {
    'results/summary.json': JSON.stringify(data, null, 2),
    'results/summary.md': markdown(data, PROFILE),
    stdout: text(data, PROFILE),
  };
}
