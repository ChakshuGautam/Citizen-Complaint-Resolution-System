// test/idgen.test.js
//
// Field names are the ones PROBED off the live v3 idgen on 2026-09-20 (see the
// header comment in src/translators/idgen.js), not the placeholders the brief
// carried: v3 /idgen/v3/generate answers {templateCode, version, id} with a
// singular `id`, and /idgen/v3/generate/bulk answers {..., count, ids:[...]}.
// toV2 receives the src/client.js result shape {status, json, raw, headers}.

// Keep the gap log out of the real /opt/hybrid-shim/gaps.jsonl.
const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');
process.env.GAP_LOG = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'idgen-test-')), 'gaps.jsonl');

const test = require('node:test');
const assert = require('node:assert');
const idgen = require('../src/translators/idgen');

function gaps() {
  if (!fs.existsSync(process.env.GAP_LOG)) return [];
  return fs
    .readFileSync(process.env.GAP_LOG, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l));
}

const CCRS_REQ = {
  RequestInfo: { apiId: 'Rainmaker', ver: '.01', ts: 0, msgId: 'm-1', userInfo: { uuid: 'u-1', tenantId: 'pg' } },
  idRequests: [
    { idName: 'pgr.servicerequestid', tenantId: 'pg', format: 'PB-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]' },
  ],
};

test('maps idRequests to the v3 generate call', () => {
  const out = idgen.toV3(JSON.parse(JSON.stringify(CCRS_REQ)));
  assert.strictEqual(out.method, 'POST');
  assert.strictEqual(out.path, '/idgen/v3/generate');
  assert.deepStrictEqual(out.body, { templateCode: 'pgr.servicerequestid' });
  assert.strictEqual(out.headers['X-Tenant-ID'], 'pg');
});

test('records the dropped 2.x format rather than discarding it silently', () => {
  const before = gaps().length;
  idgen.toV3(JSON.parse(JSON.stringify(CCRS_REQ)));
  const added = gaps().slice(before);
  const dropped = added.find((g) => g.field === 'format');
  assert.ok(dropped, 'expected a gap entry for the dropped format');
  assert.strictEqual(dropped.action, 'dropped');
  assert.strictEqual(dropped.dep, 'egov-idgen');
  assert.match(dropped.detail, /PB-PGR-\[cy:yyyy-MM-dd\]-\[SEQ_EG_PGR_ID\]/);
});

test('a batch of N identical requests becomes one bulk call', () => {
  const req = JSON.parse(JSON.stringify(CCRS_REQ));
  req.idRequests = [req.idRequests[0], JSON.parse(JSON.stringify(req.idRequests[0]))];
  const out = idgen.toV3(req);
  assert.strictEqual(out.path, '/idgen/v3/generate/bulk');
  assert.deepStrictEqual(out.body, { templateCode: 'pgr.servicerequestid', count: 2 });
});

test('the idRequest tenant wins over the RequestInfo tenant, and the override is recorded', () => {
  const req = JSON.parse(JSON.stringify(CCRS_REQ));
  req.idRequests[0].tenantId = 'pg.citya';
  const before = gaps().length;
  const out = idgen.toV3(req);
  assert.strictEqual(out.headers['X-Tenant-ID'], 'pg.citya');
  const g = gaps().slice(before).find((x) => x.field === 'X-Tenant-ID');
  assert.ok(g && g.action === 'defaulted', 'expected the tenant override to be recorded');
});

test('heterogeneous idNames are refused, not silently mis-minted', () => {
  const req = JSON.parse(JSON.stringify(CCRS_REQ));
  req.idRequests.push({ idName: 'other.id', tenantId: 'pg' });
  const before = gaps().length;
  assert.throws(() => idgen.toV3(req), /heterogeneous idNames/);
  const g = gaps().slice(before).find((x) => x.field === 'idRequests[].idName');
  assert.ok(g && g.action === 'unmapped', 'expected an unmapped gap entry');
});

test('an empty idRequests[] is refused', () => {
  assert.throws(() => idgen.toV3({ RequestInfo: {}, idRequests: [] }), /idRequests\[\] is empty/);
});

test('wraps the singular v3 id back into idResponses', () => {
  const v3 = { status: 200, json: { templateCode: 'pgr.servicerequestid', version: 'v1', id: 'PG-PGR-2026-09-20-000123' } };
  const v2 = idgen.toV2(v3, CCRS_REQ);
  assert.deepStrictEqual(v2.idResponses, [{ id: 'PG-PGR-2026-09-20-000123' }]);
});

test('wraps a bulk v3 ids[] back into idResponses', () => {
  const v3 = { status: 200, json: { templateCode: 'x', version: 'v1', count: 2, ids: ['A-1', 'A-2'] } };
  const v2 = idgen.toV2(v3, CCRS_REQ);
  assert.deepStrictEqual(v2.idResponses, [{ id: 'A-1' }, { id: 'A-2' }]);
});

test('synthesises the lowercase responseInfo CCRS deserialises, and records it as faked', () => {
  const before = gaps().length;
  const v2 = idgen.toV2({ status: 200, json: { id: 'A-1' } }, CCRS_REQ);
  assert.ok(v2.responseInfo, 'expected a lowercase responseInfo (IdGenerationResponse.java has no @JsonProperty)');
  assert.strictEqual(v2.responseInfo.status, 'SUCCESSFUL');
  assert.strictEqual(v2.responseInfo.apiId, 'Rainmaker');
  assert.strictEqual(v2.responseInfo.msgId, 'm-1');
  assert.strictEqual(v2.responseInfo.resMsgId, 'uief87324');
  const g = gaps().slice(before).find((x) => x.field === 'responseInfo');
  assert.ok(g && g.action === 'faked', 'expected the synthesised responseInfo to be recorded');
});

test('a v3 404 is NOT masked as an empty success', () => {
  const v3 = { status: 404, json: [{ code: 'NOT_FOUND', message: 'template not found', description: '', params: [] }] };
  const v2 = idgen.toV2(v3, CCRS_REQ);
  assert.deepStrictEqual(v2.idResponses, []);
  assert.strictEqual(v2.responseInfo.status, 'FAILED');
  assert.strictEqual(v2.Errors[0].code, 'NOT_FOUND');
});

test('a 200 with no id at all is recorded as unmapped', () => {
  const before = gaps().length;
  const v2 = idgen.toV2({ status: 200, json: { templateCode: 'x', version: 'v1' } }, CCRS_REQ);
  assert.deepStrictEqual(v2.idResponses, []);
  const g = gaps().slice(before).find((x) => x.field === 'idResponses');
  assert.ok(g && g.action === 'unmapped', 'expected an unmapped gap entry');
});
