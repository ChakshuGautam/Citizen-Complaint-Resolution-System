// test/idmap.test.js
//
// The durable 2.x-uuid -> v3-individual-id map, and the two substitutions the
// user translator makes with it. Every expectation below was also observed
// live on egov-subhashini (run poc-idmap.json): asked with the 2.x uuid
// f1a94c66-..., the shim queried v3 for individual bf2557f5-... and answered
// with f1a94c66-... again.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

process.env.GAP_LOG = process.env.GAP_LOG || '/tmp/idmap-test-gaps.jsonl';

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'idmap-test-'));
const mapFile = path.join(dir, 'idmap.json');
process.env.ID_MAP = mapFile;
// Pin the EMPLOYEE map to an empty temp file too. These tests are about the
// individual map alone, and without this they read the box's real
// /opt/hybrid-shim/state/empmap.json - which now holds the GRO, so the
// "unmapped uuid" case below would silently become an employee-routed one and
// assert against the wrong registry. Same isolation ID_MAP already has.
const empMapFile = path.join(dir, 'empmap.json');
fs.writeFileSync(empMapFile, JSON.stringify({ version: 1, entries: {} }));
process.env.EMP_MAP = empMapFile;

const idmap = require('../src/idmap');
const user = require('../src/translators/user');

const UUID_2X = 'f1a94c66-a4b5-46da-9492-07b7db54c69f';
const IND_V3 = 'bf2557f5-7b46-4be7-99a9-848228d41f6d';

function writeMap(doc) {
  fs.writeFileSync(mapFile, JSON.stringify(doc, null, 2));
}

function withShim(obj, p) {
  Object.defineProperty(obj, '__shim', {
    value: { method: 'POST', path: p, query: {}, headers: {}, rawBody: '' },
    enumerable: false,
    configurable: true,
  });
  Object.defineProperty(obj, '__query', { value: {}, enumerable: false, configurable: true });
  return obj;
}

function seed() {
  writeMap({
    version: 1,
    entries: {
      [UUID_2X]: {
        individualId: IND_V3,
        tenantId: 'pg',
        mobileNumber: '9999009999',
        keycloakSub: null,
        source: 'test',
        recordedAt: '2026-09-21T00:00:00.000Z',
      },
    },
  });
}

test('an absent map file is "no mapping known", not an error', () => {
  try {
    fs.unlinkSync(mapFile);
  } catch {
    /* already gone */
  }
  assert.strictEqual(idmap.lookup(UUID_2X), null);
  assert.strictEqual(idmap.size(), 0);
});

test('put() then lookup()/reverse() round-trips', () => {
  try {
    fs.unlinkSync(mapFile);
  } catch {
    /* already gone */
  }
  idmap.put({ uuid2x: UUID_2X, individualId: IND_V3, tenantId: 'pg' });
  assert.strictEqual(idmap.lookup(UUID_2X).individualId, IND_V3);
  assert.strictEqual(idmap.reverse(IND_V3).uuid2x, UUID_2X);
  assert.ok(idmap.lookup(UUID_2X).recordedAt, 'put() stamps recordedAt');
});

test('a second put() does not lose the first entry', () => {
  idmap.put({ uuid2x: 'aaaa', individualId: 'bbbb', tenantId: 'pg' });
  assert.strictEqual(idmap.lookup(UUID_2X).individualId, IND_V3);
  assert.strictEqual(idmap.lookup('aaaa').individualId, 'bbbb');
});

test('an unparseable map STOPS the lookup - it never degrades to "unmapped"', () => {
  fs.writeFileSync(mapFile, '{ this is not json');
  assert.throws(() => idmap.lookup(UUID_2X), /not valid JSON/);
});

test('two 2.x uuids on one individual are refused, not resolved by coin flip', () => {
  writeMap({
    version: 1,
    entries: {
      one: { individualId: IND_V3, tenantId: 'pg' },
      two: { individualId: IND_V3, tenantId: 'pg' },
    },
  });
  assert.throws(() => idmap.reverse(IND_V3), /same individual/);
});

test('a map written by a future version is refused', () => {
  writeMap({ version: 99, entries: {} });
  assert.throws(() => idmap.lookup(UUID_2X), /version/);
});

test('search sends the v3 individual id, not the 2.x uuid', () => {
  seed();
  const req = withShim({ uuid: [UUID_2X], tenantId: 'pg', RequestInfo: { userInfo: { tenantId: 'pg', uuid: UUID_2X } } }, '/user/_search');
  const v3 = user.toV3(req);
  assert.ok(v3.path.includes(`id=${IND_V3}`), `expected the mapped id in ${v3.path}`);
  assert.ok(!v3.path.includes(UUID_2X), 'the 2.x uuid must not reach v3');
});

test('an unmapped uuid is passed through unchanged (pre-map behaviour preserved)', () => {
  seed();
  // The GRO. Not in the INDIVIDUAL map (and, with EMP_MAP pinned empty above,
  // not in the employee map either), so this is the pure pass-through case.
  const other = '21fc9b81-9e52-43a5-ac1a-00db84dc2a6a';
  const req = withShim({ uuid: [other], tenantId: 'pg', RequestInfo: { userInfo: { tenantId: 'pg', uuid: other } } }, '/user/_search');
  const v3 = user.toV3(req);
  assert.ok(v3.path.includes(`id=${other}`), `expected the raw uuid in ${v3.path}`);
});

test('a map entry from another tenant is NOT used', () => {
  seed();
  const req = withShim({ uuid: [UUID_2X], tenantId: 'pb', RequestInfo: { userInfo: { tenantId: 'pb', uuid: UUID_2X } } }, '/user/_search');
  const v3 = user.toV3(req);
  assert.ok(v3.path.includes(`id=${UUID_2X}`), 'a cross-tenant entry must not be substituted');
  assert.ok(!v3.path.includes(IND_V3));
});

test('the answer comes back wearing the 2.x uuid CCRS asked with', () => {
  seed();
  const req = withShim({ uuid: [UUID_2X], tenantId: 'pg', RequestInfo: { userInfo: { tenantId: 'pg', uuid: UUID_2X } } }, '/user/_search');
  const out = user.toV2(
    { status: 200, json: { individuals: [{ id: IND_V3, givenName: 'Baseline', familyName: 'Citizen', mobileNumber: '9999009999', isActive: true, auditDetail: { createdTime: 1758421825168 } }] } },
    req
  );
  assert.strictEqual(out.user.length, 1);
  assert.strictEqual(out.user[0].uuid, UUID_2X, 'enrichUsers keys its map by this value');
  assert.strictEqual(out.user[0].name, 'Baseline Citizen');
});

test('an individual the map does not know keeps its v3 id - nothing is invented', () => {
  seed();
  const req = withShim({ uuid: [UUID_2X], tenantId: 'pg', RequestInfo: { userInfo: { tenantId: 'pg', uuid: UUID_2X } } }, '/user/_search');
  const out = user.toV2({ status: 200, json: { individuals: [{ id: 'unknown-individual', givenName: 'Nobody', isActive: true }] } }, req);
  assert.strictEqual(out.user[0].uuid, 'unknown-individual');
});
