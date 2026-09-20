// test/context.test.js
//
// context.js records a gap whenever it cannot derive a header, so the gap log
// is redirected to a temp dir BEFORE the module is required - the same
// convention as idgen/boundary/workflow/server. Without this the suite would
// append to the real /opt/hybrid-shim/gaps.jsonl, which is the deliverable.
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');
process.env.GAP_LOG = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'context-test-')), 'gaps.jsonl');

const test = require('node:test');
const assert = require('node:assert');
const context = require('../src/context');

function gaps() {
  if (!fs.existsSync(process.env.GAP_LOG)) return [];
  return fs
    .readFileSync(process.env.GAP_LOG, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l));
}

test('maps RequestInfo userInfo to v3 headers', () => {
  const body = { RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'mz' } } };
  assert.deepStrictEqual(context.toHeaders(body), {
    'X-Tenant-ID': 'mz',
    'X-User-ID': 'u-1',
  });
});

test('falls back to RequestInfo.tenantId', () => {
  const body = { RequestInfo: { tenantId: 'mz', userInfo: { uuid: 'u-2' } } };
  assert.strictEqual(context.toHeaders(body)['X-Tenant-ID'], 'mz');
});

// --- a header that cannot be derived is recorded, not defaulted silently ---

test('a resolvable RequestInfo records nothing', () => {
  const before = gaps().length;
  context.toHeaders({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'mz' } } }, '/egov-idgen/id/_generate');
  assert.strictEqual(gaps().length, before, 'no gap is due when both headers resolve');
});

test('a missing tenant is gap-logged instead of silently becoming an empty header', () => {
  const before = gaps().length;
  // The live shape this really happens on: the 2.x boundary search posts a
  // BARE RequestInfo, so body.RequestInfo does not exist at all.
  const h = context.toHeaders({ userInfo: { uuid: 'u-1', tenantId: 'pg' } }, '/boundary-service/boundary/_search');
  assert.strictEqual(h['X-Tenant-ID'], '', 'behaviour is unchanged: still an empty header');
  assert.strictEqual(h['X-User-ID'], '');
  const added = gaps().slice(before);
  const t = added.find((g) => g.field === 'X-Tenant-ID');
  assert.ok(t, 'the empty X-Tenant-ID must be recorded');
  assert.strictEqual(t.action, 'defaulted');
  assert.strictEqual(t.dep, 'shim-context');
  assert.match(t.detail, /RequestInfo\.userInfo\.tenantId/);
  assert.match(t.detail, /boundary-service\/boundary\/_search/);
});

test('a missing acting user is recorded separately from the tenant', () => {
  const before = gaps().length;
  const h = context.toHeaders({ RequestInfo: { tenantId: 'pg', userInfo: {} } }, '/egov-workflow-v2/x');
  assert.strictEqual(h['X-Tenant-ID'], 'pg');
  assert.strictEqual(h['X-User-ID'], '');
  const added = gaps().slice(before);
  assert.strictEqual(added.some((g) => g.field === 'X-Tenant-ID'), false, 'the tenant resolved');
  const u = added.find((g) => g.field === 'X-User-ID');
  assert.ok(u, 'the empty X-User-ID must be recorded');
  assert.strictEqual(u.action, 'defaulted');
  assert.match(u.detail, /RequestInfo\.userInfo\.uuid/);
});

test('an unnamed caller still produces a usable entry', () => {
  const before = gaps().length;
  context.toHeaders({}, undefined);
  const added = gaps().slice(before);
  assert.strictEqual(added.length, 2, 'one entry per undeliverable header');
  assert.ok(added.every((g) => /caller not named/.test(g.detail)));
});
