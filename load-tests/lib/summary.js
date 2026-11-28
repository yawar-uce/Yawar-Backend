// Renders the end-of-test data of k6 (handleSummary) as a short Markdown table and a console text.
// Kept in its own module so it can be checked without running a test.

const GATE = 'api:donors_me';

function metric(data, name) {
  const m = data.metrics[name];
  if (!m) return null;
  return m.values || m;   // k6 < 1.0 nests the numbers under `values`
}

function thresholdRows(data) {
  const rows = [];
  for (const name of Object.keys(data.metrics)) {
    const m = data.metrics[name];
    if (!m.thresholds) continue;
    for (const expr of Object.keys(m.thresholds)) {
      const t = m.thresholds[expr];
      const ok = typeof t === 'object' ? t.ok : !!t;
      rows.push({ metric: name, expr, ok });
    }
  }
  return rows;
}

const ms = (v) => (v === undefined || v === null ? 'n/a' : `${v.toFixed(0)} ms`);
const pct = (v) => (v === undefined || v === null ? 'n/a' : `${(v * 100).toFixed(2)} %`);

export function markdown(data, profile) {
  const dur = metric(data, `http_req_duration{expected_response:true,${GATE}}`);
  const failed = metric(data, `http_req_failed{${GATE}}`);
  const reqs = metric(data, `http_reqs{${GATE}}`);
  const hits = metric(data, 'donors_me_not_modified');
  const limited = metric(data, 'donors_me_rate_limited');
  const refreshFail = metric(data, 'token_refresh_failures');
  const out = [];
  out.push(`### Load test (k6, profile \`${profile}\`) on GET /api/v1/donors/me`);
  out.push('');
  out.push('| Metric | Value |');
  out.push('|---|---|');
  out.push(`| Requests to the gateway | ${reqs ? reqs.count : 0} (${reqs ? reqs.rate.toFixed(1) : 0}/s) |`);
  out.push(`| p50 / p90 / p95 / p99 (expected responses) | ${dur ? [dur.med, dur['p(90)'], dur['p(95)'], dur['p(99)']].map(ms).join(' / ') : 'n/a'} |`);
  out.push(`| max | ${dur ? ms(dur.max) : 'n/a'} |`);
  out.push(`| Error rate (non 200/304) | ${failed ? pct(failed.rate) : 'n/a'} |`);
  out.push(`| 304 Not Modified (If-None-Match) | ${hits ? hits.count : 0} |`);
  out.push(`| 429 rate limited | ${limited ? limited.count : 0} |`);
  out.push(`| Token refresh failures | ${refreshFail ? refreshFail.count : 0} |`);
  out.push('');
  out.push('| Threshold | Metric | Result |');
  out.push('|---|---|---|');
  for (const r of thresholdRows(data)) {
    out.push(`| \`${r.expr}\` | \`${r.metric}\` | ${r.ok ? 'passed' : '**FAILED**'} |`);
  }
  out.push('');
  return out.join('\n');
}

export function text(data, profile) {
  return markdown(data, profile).replace(/^### /gm, '').replace(/\*\*/g, '').replace(/`/g, '') + '\n';
}
