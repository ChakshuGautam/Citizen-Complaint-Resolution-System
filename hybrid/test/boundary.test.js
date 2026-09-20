// test/boundary.test.js
//
// Redirect the gap log BEFORE the translator is required, so running the
// suite never appends to the real /opt/hybrid-shim/gaps.jsonl.
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');
process.env.GAP_LOG = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'gaps-boundary-')), 'gaps.jsonl');

const test = require('node:test');
const assert = require('node:assert');
const boundary = require('../src/translators/boundary');

function gapLines() {
  if (!fs.existsSync(process.env.GAP_LOG)) return [];
  return fs
    .readFileSync(process.env.GAP_LOG, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l));
}

// Mirrors server.js makeReq2x(): a non-enumerable __shim carrying the inbound
// 2.x transport context. Every translator call goes through one, because the
// translator no longer defaults to the boundary search when the route cannot
// be determined - it throws. The brief's two literal cases below were written
// against that removed fallback and are restated here with a real __shim.
function withShim(body, path, query) {
  const obj = body && typeof body === 'object' ? body : {};
  Object.defineProperty(obj, '__shim', {
    value: { method: 'POST', path, query: query || {}, headers: {}, rawBody: '' },
    enumerable: false,
    configurable: true,
  });
  return obj;
}

const BOUNDARY_PATH = '/boundary-service/boundary/_search';

// --- the two cases the brief specifies ----------------------------------

test('moves query-string codes onto the v3 GET', () => {
  const out = boundary.toV3(
    withShim(
      { __query: { tenantId: 'mz', codes: 'A,B', limit: '10', offset: '0' },
        RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'mz' } } },
      BOUNDARY_PATH,
      { tenantId: 'mz', codes: 'A,B', limit: '10', offset: '0' }
    )
  );
  assert.strictEqual(out.method, 'GET');
  assert.ok(out.path.startsWith('/boundary/v3/boundaries'));
  assert.ok(out.path.includes('codes=A%2CB') || out.path.includes('codes=A,B'));
});

test('returns a boundary array under the 2.x key', () => {
  const v2 = boundary.toV2({ boundaries: [{ code: 'A', name: 'A' }] }, withShim({}, BOUNDARY_PATH, {}));
  assert.strictEqual(v2.boundary.length, 1);
});

// --- the real 2.x wire shape -------------------------------------------

// CCRS posts the BARE RequestInfo as the body, so the identity fields are at
// the top level and req2x.RequestInfo does not exist.
function bareReq(query) {
  const req = {
    apiId: 'Rainmaker',
    ver: '.01',
    ts: null,
    action: '_search',
    did: '1',
    key: '',
    msgId: 'probe',
    userInfo: { uuid: 'f1a94c66-a4b5-46da-9492-07b7db54c69f', tenantId: 'pg', type: 'CITIZEN' },
  };
  Object.defineProperty(req, '__shim', {
    value: { method: 'POST', path: '/boundary-service/boundary/_search', query, headers: {}, rawBody: '' },
    enumerable: false,
  });
  return req;
}

test('reads the query off __shim.query when __query is absent', () => {
  const out = boundary.toV3(bareReq({ limit: '10', offset: '0', tenantId: 'pg', codes: 'SUN01_LOCALITY' }));
  assert.strictEqual(out.method, 'GET');
  assert.ok(out.path.includes('codes=SUN01_LOCALITY'));
  assert.ok(out.path.includes('limit=10'));
  assert.ok(out.path.includes('offset=0'));
});

test('tenant comes from the request, never hardcoded, and rides as a header', () => {
  const out = boundary.toV3(bareReq({ tenantId: 'pg', codes: 'SUN01_LOCALITY' }));
  assert.strictEqual(out.headers['X-Tenant-ID'], 'pg');
  assert.strictEqual(out.headers['X-User-ID'], 'f1a94c66-a4b5-46da-9492-07b7db54c69f');
  // tenantId is a header dimension in v3, not a query param.
  assert.ok(!out.path.includes('tenantId='));
});

test('a different tenant in the query produces a different header', () => {
  const out = boundary.toV3(bareReq({ tenantId: 'pg.citya', codes: 'W1_ADMIN_WARD' }));
  assert.strictEqual(out.headers['X-Tenant-ID'], 'pg.citya');
});

test('falls back to the bare RequestInfo tenant when the query has none', () => {
  const out = boundary.toV3(bareReq({ codes: 'SUN01_LOCALITY' }));
  assert.strictEqual(out.headers['X-Tenant-ID'], 'pg');
});

test('a GET carries no body', () => {
  const out = boundary.toV3(bareReq({ tenantId: 'pg', codes: 'SUN01_LOCALITY' }));
  assert.strictEqual(out.body, undefined);
});

// --- response translation, against the live v3 payload ------------------

// Captured verbatim from GET http://d3-kong:8000/boundary/v3/boundaries?codes=SUN01_LOCALITY
const LIVE_V3 = {
  status: 200,
  json: {
    boundary: [
      {
        id: '2f8013e1-9deb-442b-b026-b8a25df1daeb',
        tenantId: 'pg',
        code: 'SUN01_LOCALITY',
        geometry: { type: 'Point', coordinates: [74.87155, 31.63089] },
        additionalAttributes: {},
        auditDetails: {
          createdBy: 'hybrid-shim-task4',
          modifiedBy: 'hybrid-shim-task4',
          createdTime: 1789875432046,
          modifiedTime: 1789875432046,
        },
      },
    ],
  },
};

test('rebuilds the capitalised 2.x Boundary key', () => {
  const v2 = boundary.toV2(LIVE_V3, bareReq({ tenantId: 'pg', codes: 'SUN01_LOCALITY' }));
  assert.ok(Array.isArray(v2.Boundary), 'Boundary must be the capitalised 2.x key');
  assert.strictEqual(v2.Boundary.length, 1);
  assert.strictEqual(v2.Boundary[0].code, 'SUN01_LOCALITY');
});

test('renames additionalAttributes to additionalDetails', () => {
  const v2 = boundary.toV2(LIVE_V3, bareReq({ tenantId: 'pg', codes: 'SUN01_LOCALITY' }));
  assert.deepStrictEqual(v2.Boundary[0].additionalDetails, {});
  assert.ok(!('additionalAttributes' in v2.Boundary[0]));
});

test('renames modifiedBy/modifiedTime to lastModifiedBy/lastModifiedTime', () => {
  const v2 = boundary.toV2(LIVE_V3, bareReq({ tenantId: 'pg', codes: 'SUN01_LOCALITY' }));
  assert.deepStrictEqual(v2.Boundary[0].auditDetails, {
    createdBy: 'hybrid-shim-task4',
    lastModifiedBy: 'hybrid-shim-task4',
    createdTime: 1789875432046,
    lastModifiedTime: 1789875432046,
  });
});

test('geometry and id are carried across untouched', () => {
  const v2 = boundary.toV2(LIVE_V3, bareReq({ tenantId: 'pg', codes: 'SUN01_LOCALITY' }));
  assert.strictEqual(v2.Boundary[0].id, '2f8013e1-9deb-442b-b026-b8a25df1daeb');
  assert.deepStrictEqual(v2.Boundary[0].geometry, { type: 'Point', coordinates: [74.87155, 31.63089] });
  assert.strictEqual(v2.Boundary[0].tenantId, 'pg');
});

test('the fabricated ResponseInfo echoes the inbound RequestInfo', () => {
  const v2 = boundary.toV2(LIVE_V3, bareReq({ tenantId: 'pg', codes: 'SUN01_LOCALITY' }));
  assert.deepStrictEqual(v2.ResponseInfo, {
    apiId: 'Rainmaker',
    ver: '.01',
    ts: null,
    resMsgId: 'uief87324',
    msgId: 'probe',
    status: 'successful',
  });
});

test('a v3 miss stays a miss - an empty list is never faked into a hit', () => {
  const v2 = boundary.toV2({ status: 200, json: { boundary: [] } }, bareReq({ tenantId: 'pg', codes: 'NOPE' }));
  assert.deepStrictEqual(v2.Boundary, []);
  assert.deepStrictEqual(v2.boundary, []);
});

// --- gap logging --------------------------------------------------------

test('a multi-code search is recorded as unmapped, and still passed through', () => {
  const before = gapLines().length;
  const out = boundary.toV3(bareReq({ tenantId: 'pg', codes: 'PG_CITYA,SUN01_LOCALITY' }));
  const added = gapLines().slice(before);
  const entry = added.find((e) => e.field === 'codes' && e.action === 'unmapped');
  assert.ok(entry, 'a multi-code search must be gap-logged');
  assert.ok(/MULTIPLE codes/.test(entry.detail));
  // passed through unchanged, not silently split or dropped
  assert.ok(out.path.includes('codes=PG_CITYA%2CSUN01_LOCALITY'));
});

test('a missing tenant is recorded as unmapped rather than defaulted to one', () => {
  const before = gapLines().length;
  const out = boundary.toV3(withShim({ __query: { codes: 'SUN01_LOCALITY' } }, BOUNDARY_PATH, { codes: 'SUN01_LOCALITY' }));
  const added = gapLines().slice(before);
  assert.ok(added.some((e) => e.field === 'tenantId' && e.action === 'unmapped'));
  assert.strictEqual(out.headers['X-Tenant-ID'], '');
});

test('a codeless search is recorded as unmapped - v3 makes codes required', () => {
  const before = gapLines().length;
  const out = boundary.toV3(bareReq({ tenantId: 'pg', limit: '10', offset: '0' }));
  const added = gapLines().slice(before);
  assert.ok(added.some((e) => e.field === 'codes' && e.action === 'unmapped'));
  assert.ok(!out.path.includes('codes='));
});

test('the renames and the fabricated envelope are both gap-logged', () => {
  const before = gapLines().length;
  boundary.toV2(LIVE_V3, bareReq({ tenantId: 'pg', codes: 'SUN01_LOCALITY' }));
  const added = gapLines().slice(before);
  assert.ok(added.some((e) => e.field === 'Boundary[]' && e.action === 'defaulted'));
  assert.ok(added.some((e) => e.field === 'ResponseInfo' && e.action === 'faked'));
  assert.ok(added.some((e) => e.field === 'boundary' && e.action === 'faked'));
  assert.ok(added.every((e) => e.dep === 'boundary-service'));
});

test('exports a dep so exception gap entries name the dependency', () => {
  assert.strictEqual(boundary.dep, 'boundary-service');
});

// --- 2. boundary-relationships/_search ---------------------------------
//
// The SECOND endpoint behind EGOV_BOUNDARY_HOST. Registering one translator
// for the whole /boundary-service prefix and answering a Boundary envelope to
// this call is what made the first switchover return zero complaints with a
// 200 and no error anywhere.

// BoundaryHierarchyExpander.fetch WRAPS the RequestInfo, unlike validateBoundary.
function relReq(query) {
  const req = {
    RequestInfo: {
      apiId: 'Rainmaker',
      ver: '.01',
      ts: null,
      msgId: 'probe',
      userInfo: { uuid: '21fc9b81-9e52-43a5-ac1a-00db84dc2a6a', tenantId: 'pg', type: 'EMPLOYEE' },
    },
  };
  Object.defineProperty(req, '__shim', {
    value: {
      method: 'POST',
      path: '/boundary-service/boundary-relationships/_search',
      query,
      headers: {},
      rawBody: '',
    },
    enumerable: false,
  });
  return req;
}

const REL_QUERY = { tenantId: 'pg', hierarchyType: 'ADMIN', codes: 'PG_STATE', includeChildren: 'true' };

// Captured verbatim from GET http://d3-kong:8000/boundary/v3/relationship
//   ?hierarchyType=ADMIN&codes=PG_STATE&includeChildren=true
const LIVE_V3_REL = {
  status: 200,
  json: {
    tenantBoundary: [
      {
        tenantId: 'pg',
        hierarchyType: 'ADMIN',
        boundary: [
          {
            id: 'c357f737-0f06-4868-b875-0145b4de176a',
            code: 'PG_STATE',
            boundaryType: 'State',
            auditDetails: { createdBy: 'x', modifiedBy: 'x', createdTime: 1, modifiedTime: 1 },
            children: [
              {
                id: 'e04bae5c-14f3-464c-a230-69eb28ef5d6c',
                code: 'PG_CITYA',
                boundaryType: 'City',
                auditDetails: { createdBy: 'x', modifiedBy: 'x', createdTime: 1, modifiedTime: 1 },
                children: [
                  {
                    id: '1241e689-66a7-4580-8a9b-1c8fdef3431e',
                    code: 'PG_CITYA_B1',
                    boundaryType: 'Block',
                    children: [
                      {
                        // a LEAF: v3 omits `children` entirely
                        id: 'd396fb06-2cb8-40b4-bf0d-564095a00aca',
                        code: 'SUN01_LOCALITY',
                        boundaryType: 'Locality',
                        auditDetails: { createdBy: 'x', modifiedBy: 'x', createdTime: 1, modifiedTime: 1 },
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  },
};

test('the relationship path is routed to the v3 relationship endpoint, not boundaries', () => {
  const out = boundary.toV3(relReq(REL_QUERY));
  assert.strictEqual(out.method, 'GET');
  assert.ok(out.path.startsWith('/boundary/v3/relationship'), `got ${out.path}`);
  assert.ok(out.path.includes('hierarchyType=ADMIN'));
  assert.ok(out.path.includes('codes=PG_STATE'));
  assert.ok(out.path.includes('includeChildren=true'));
  // tenancy is a header in v3, never a query param
  assert.ok(!out.path.includes('tenantId='));
  assert.strictEqual(out.headers['X-Tenant-ID'], 'pg');
});

test('the boundary path is still routed to the v3 boundaries endpoint', () => {
  const out = boundary.toV3(bareReq({ tenantId: 'pg', codes: 'SUN01_LOCALITY' }));
  assert.ok(out.path.startsWith('/boundary/v3/boundaries'), `got ${out.path}`);
});

test('the relationship reply is rebuilt under the capitalised TenantBoundary key', () => {
  const v2 = boundary.toV2(LIVE_V3_REL, relReq(REL_QUERY));
  assert.ok(Array.isArray(v2.TenantBoundary), 'TenantBoundary must be the capitalised 2.x key');
  assert.strictEqual(v2.TenantBoundary.length, 1);
  assert.strictEqual(v2.TenantBoundary[0].tenantId, 'pg');
  assert.strictEqual(v2.TenantBoundary[0].hierarchyType, 'ADMIN');
  assert.strictEqual(v2.TenantBoundary[0].boundary[0].code, 'PG_STATE');
});

// This is the assertion that would have caught the first switchover failure.
test('BoundaryHierarchyExpander.collect() finds every descendant code', () => {
  const v2 = boundary.toV2(LIVE_V3_REL, relReq(REL_QUERY));
  const codes = new Set();
  const collect = (n) => {
    if (n.code) codes.add(n.code);
    (n.children || []).forEach(collect);
  };
  (v2.TenantBoundary || []).forEach((tb) => (tb.boundary || []).forEach(collect));
  assert.deepStrictEqual(
    [...codes].sort(),
    ['PG_CITYA', 'PG_CITYA_B1', 'PG_STATE', 'SUN01_LOCALITY']
  );
});

test('a leaf gets its 2.x children:[] back, and auditDetails is dropped', () => {
  const v2 = boundary.toV2(LIVE_V3_REL, relReq(REL_QUERY));
  const leaf = v2.TenantBoundary[0].boundary[0].children[0].children[0].children[0];
  assert.strictEqual(leaf.code, 'SUN01_LOCALITY');
  assert.deepStrictEqual(leaf.children, []);
  assert.ok(!('auditDetails' in leaf), '2.x emits no auditDetails on relationship nodes');
  assert.deepStrictEqual(Object.keys(leaf).sort(), ['boundaryType', 'children', 'code', 'id']);
});

test('the relationship rename is gap-logged - it is the one that silently blanks the search', () => {
  const before = gapLines().length;
  boundary.toV2(LIVE_V3_REL, relReq(REL_QUERY));
  const added = gapLines().slice(before);
  const e = added.find((x) => x.field === 'TenantBoundary[]' && x.action === 'defaulted');
  assert.ok(e, 'the tenantBoundary -> TenantBoundary rename must be gap-logged');
  assert.ok(/EMPTY code set/.test(e.detail));
  assert.ok(added.some((x) => x.field === 'TenantBoundary[].boundary[].auditDetails' && x.action === 'dropped'));
  assert.ok(added.some((x) => x.field === 'TenantBoundary[].boundary[].children' && x.action === 'defaulted'));
});

test('an unknown /boundary-service path throws and is gap-logged, never guessed at', () => {
  const req = {};
  Object.defineProperty(req, '__shim', {
    value: { method: 'POST', path: '/boundary-service/boundary/_create', query: {}, headers: {}, rawBody: '' },
    enumerable: false,
  });
  const before = gapLines().length;
  assert.throws(() => boundary.toV3(req), /unsupported 2\.x boundary path/);
  const added = gapLines().slice(before);
  assert.ok(added.some((e) => e.action === 'unmapped' && e.field === '/boundary-service/boundary/_create'));
});

// --- the removed shape/absence fallback ---------------------------------
//
// Tasks 5 and 6 removed the same branch from workflow.js and user.js. This
// translator had it too, in two places: toV3 defaulted to the boundary search
// whenever req2x carried no __shim, and toV2 defaulted to it for ANY route it
// could not name. Both are gone.

test('toV3 with no __shim throws instead of defaulting to the boundary search', () => {
  assert.throws(() => boundary.toV3({ __query: { tenantId: 'pg', codes: 'SUN01_LOCALITY' } }),
    /carried no __shim transport context/);
  assert.throws(() => boundary.toV3(undefined), /carried no __shim transport context/);
  assert.throws(() => boundary.toV3(null), /carried no __shim transport context/);
});

test('toV2 with no __shim throws rather than answering a TenantBoundary-less envelope', () => {
  // This is the exact silent failure: a relationship reply rebuilt as a
  // boundary envelope has no TenantBoundary key, so BoundaryHierarchyExpander
  // yields an empty jurisdiction and the employee inbox comes back empty, 200.
  assert.throws(() => boundary.toV2(LIVE_V3_REL, {}), /carried no __shim transport context/);
  assert.throws(() => boundary.toV2({ status: 200, json: { boundary: [] } }, {}),
    /carried no __shim transport context/);
});

test('toV2 on an unrecognised path throws instead of falling through to boundaryToV2', () => {
  const req = {};
  Object.defineProperty(req, '__shim', {
    value: { method: 'POST', path: '/boundary-service/boundary/_create', query: {}, headers: {}, rawBody: '' },
    enumerable: false,
  });
  assert.throws(() => boundary.toV2({ status: 200, json: {} }, req), /unsupported 2\.x boundary path/);
});

test('a missing __shim is gap-logged as unmapped, not swallowed', () => {
  const before = gapLines().length;
  assert.throws(() => boundary.toV3({}), /carried no __shim/);
  const added = gapLines().slice(before);
  const e = added.find((x) => x.field === '(no path: req2x carried no __shim)');
  assert.ok(e, 'the missing-context case must be gap-logged');
  assert.strictEqual(e.action, 'unmapped');
  assert.strictEqual(e.dep, 'boundary-service');
  assert.match(e.detail, /zero-complaint employee inbox/);
});
