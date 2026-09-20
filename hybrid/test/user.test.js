// test/user.test.js
//
// Every expectation here was checked against the LIVE d3-individual service
// before it was written down (see the probe log in src/translators/user.js).
const test = require('node:test');
const assert = require('node:assert');

// Keep the gap log out of the repo copy while unit tests run.
process.env.GAP_LOG = process.env.GAP_LOG || '/tmp/user-test-gaps.jsonl';
const user = require('../src/translators/user');
const fs = require('node:fs');

// Reads only the lines a single call appended, so one test cannot see another's.
function readGapLog(fn) {
  const file = process.env.GAP_LOG;
  const before = fs.existsSync(file) ? fs.readFileSync(file, 'utf8').split('\n').length : 1;
  fn();
  const lines = fs.readFileSync(file, 'utf8').split('\n').slice(before - 1).filter(Boolean);
  return lines.map((l) => JSON.parse(l));
}

// ---------------------------------------------------------------------------
// The task brief's own two contract tests, verbatim.
// ---------------------------------------------------------------------------

test('maps a 2.x user search by mobile to the v3 individual search', () => {
  const out = user.toV3({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'mz' } },
                          userName: '9999999999', tenantId: 'mz', userType: 'CITIZEN' },
                        { path: '/user/_search' });
  assert.strictEqual(out.method, 'GET');
  assert.ok(out.path.includes('/individual/v3/individuals'));
});

test('rebuilds the 2.x user envelope', () => {
  const v2 = user.toV2({ individuals: [{ id: 'i-1', name: { givenName: 'Ada' }, mobileNumber: '9999999999' }] },
                       { path: '/user/_search' });
  assert.strictEqual(v2.user.length, 1);
  assert.strictEqual(v2.user[0].userName, '9999999999');
});

// ---------------------------------------------------------------------------
// search -> v3
// ---------------------------------------------------------------------------

const SEARCH = { path: '/user/_search' };
function searchReq(extra) {
  return Object.assign(
    { RequestInfo: { apiId: 'Rainmaker', userInfo: { uuid: 'caller-1', tenantId: 'pg' } }, tenantId: 'pg' },
    extra
  );
}

test('a userName search becomes a v3 mobileNumber filter - v3 has no userName', () => {
  const out = user.toV3(searchReq({ userName: '9999009999', userType: 'CITIZEN', active: true }), SEARCH);
  assert.strictEqual(out.method, 'GET');
  assert.ok(out.path.includes('mobileNumber=9999009999'));
  assert.ok(!out.path.includes('userName'));
  assert.strictEqual(out.headers['X-Tenant-ID'], 'pg');
});

test('a mobileNumber search wins over userName and is sent exact', () => {
  const out = user.toV3(searchReq({ userName: 'ignored', mobileNumber: '8888008888' }), SEARCH);
  assert.ok(out.path.includes('mobileNumber=8888008888'));
});

test('a uuid list becomes REPEATED ?id= parameters, never the comma form', () => {
  // Verified live: ?id=a&id=b returns both; ?id=a,b returns 400.
  const out = user.toV3(searchReq({ uuid: ['aaa-1', 'bbb-2'] }), SEARCH);
  assert.ok(out.path.includes('id=aaa-1'));
  assert.ok(out.path.includes('id=bbb-2'));
  assert.ok(!out.path.includes('id=aaa-1%2Cbbb-2'));
  assert.ok(!out.path.includes('aaa-1,bbb-2'));
});

test('the page size is pinned to the number of uuids asked for, not the v3 default of 20', () => {
  const out = user.toV3(searchReq({ uuid: ['a', 'b', 'c'] }), SEARCH);
  assert.ok(out.path.includes('size=3'), out.path);
  assert.ok(out.path.includes('page=1'));
});

test('more uuids than v3 will page THROWS rather than silently truncating', () => {
  const many = Array.from({ length: 101 }, (_, i) => `u-${i}`);
  assert.throws(() => user.toV3(searchReq({ uuid: many }), SEARCH), /caps size at 100/);
});

test('a search with no usable criterion THROWS rather than asking v3 for the whole tenant', () => {
  assert.throws(
    () => user.toV3(searchReq({ userType: 'CITIZEN', active: true, roleCodes: ['GRO'] }), SEARCH),
    /refusing to translate into an unfiltered v3 individual search/
  );
});

test('active=false THROWS - v3 cannot be asked for disabled users', () => {
  assert.throws(() => user.toV3(searchReq({ uuid: ['a'], active: false }), SEARCH), /cannot filter active=false/);
});

test('userType is NOT forwarded: v3 has no user type and would ignore the parameter', () => {
  const out = user.toV3(searchReq({ uuid: ['a'], userType: 'CITIZEN' }), SEARCH);
  assert.ok(!out.path.includes('userType'));
  assert.ok(!out.path.toLowerCase().includes('type='));
});

test('no unknown v3 filter is ever sent - the service ignores them and answers 200 with everything', () => {
  const out = user.toV3(searchReq({ uuid: ['a'], name: 'Ada', emailId: 'a@b.c', roleCodes: ['CITIZEN'] }), SEARCH);
  const qs = out.path.split('?')[1] || '';
  const names = qs.split('&').map((kv) => kv.split('=')[0]).sort();
  assert.deepStrictEqual([...new Set(names)].sort(), ['id', 'page', 'size']);
});

// ---------------------------------------------------------------------------
// search -> 2.x
// ---------------------------------------------------------------------------

const LIVE_INDIVIDUAL = {
  id: '56a53054-b7e9-49b0-b508-786c0eb2b761',
  individualId: 'IND-f61cafe2',
  givenName: 'Zzprobe',
  familyName: 'Shim',
  gender: 'MALE',
  mobileNumber: '9999009999',
  mobileNumberVerified: false,
  email: 'zz@example.com',
  emailVerified: false,
  isActive: true,
  version: 1,
  auditDetail: { createdBy: 'x', createdTime: 1789879106989, modifiedBy: 'x', modifiedTime: 1789879106989 },
};

test('a live v3 individual becomes a CCRS User with all seven fields CCRS needs', () => {
  const v2 = user.toV2({ status: 200, json: { totalCount: 1, individuals: [LIVE_INDIVIDUAL] } },
                       searchReq({ uuid: ['56a53054-b7e9-49b0-b508-786c0eb2b761'], userType: 'CITIZEN' }));
  const u = v2.user[0];
  assert.strictEqual(u.uuid, '56a53054-b7e9-49b0-b508-786c0eb2b761');
  assert.strictEqual(u.userName, '9999009999');
  assert.strictEqual(u.mobileNumber, '9999009999');
  assert.strictEqual(u.name, 'Zzprobe Shim');
  assert.strictEqual(u.emailId, 'zz@example.com');
  assert.strictEqual(u.tenantId, 'pg');
  assert.strictEqual(u.active, true);
  // NOT invented: v3 reports neither, so the search path reports neither
  assert.strictEqual(u.type, null);
  assert.deepStrictEqual(u.roles, []);
  // dropped, because v3 has no numeric key
  assert.strictEqual(u.id, null);
});

// The search path used to stamp roles=[{CITIZEN}] / type="CITIZEN" on every
// individual it returned, which meant getFirstRoleNameByUuid(<employee uuid>)
// answered "Citizen". v3 reports neither field, so the shim reports neither.
test('the search path invents NO roles and NO type, even for an unknown person', () => {
  const employeeish = Object.assign({}, LIVE_INDIVIDUAL, {
    id: 'emp-uuid', givenName: 'Gro', familyName: 'Officer', mobileNumber: '7000070000',
  });
  const v2 = user.toV2({ status: 200, json: { totalCount: 1, individuals: [employeeish] } },
                       searchReq({ uuid: ['emp-uuid'], userType: 'CITIZEN' }));
  const u = v2.user[0];
  assert.deepStrictEqual(u.roles, [], 'an employee must not come back stamped CITIZEN');
  assert.strictEqual(u.type, null, 'no user type may be invented on the search path');
  // CCRS's only consumer is null-tolerant: getFirstRoleNameByUuid returns null
  // for an empty roles list, and getAssigneeName then falls back to "NA".
  assert.ok(Array.isArray(u.roles) && u.roles.length === 0);
});

test('the search path still gap-logs WHY roles and type are empty', () => {
  const log = readGapLog(() => {
    user.toV2({ status: 200, json: { individuals: [LIVE_INDIVIDUAL] } }, searchReq({ uuid: ['x'] }));
  });
  const roles = log.filter((e) => e.field === 'user.roles[]');
  const type = log.filter((e) => e.field === 'user.type');
  assert.strictEqual(roles.length, 1);
  assert.strictEqual(type.length, 1);
  assert.strictEqual(roles[0].action, 'dropped');
  assert.strictEqual(type[0].action, 'dropped');
  // the finding: v3 has no such fields, and the real values are Keycloak's
  assert.match(roles[0].detail, /Keycloak realm roles/);
  assert.match(type[0].detail, /Keycloak realm roles/);
  // and the blast radius is stated honestly, not as a privilege escalation
  assert.match(roles[0].detail, /NOT A PRIVILEGE PATH/);
  assert.match(roles[0].detail, /getAssigneeName/);
});

test('the create path still ECHOES the caller\'s roles - that is not an invention', () => {
  const created = Object.assign({}, LIVE_INDIVIDUAL, { id: 'new-uuid' });
  const v2 = user.toV2({ status: 201, json: created }, createReq());
  assert.deepStrictEqual(v2.user[0].roles, [{ code: 'CITIZEN', name: 'Citizen', tenantId: 'pg' }]);
  assert.strictEqual(v2.user[0].type, 'CITIZEN');
});

test('createdDate is emitted in the exact format UserUtils.parseResponse demands', () => {
  const v2 = user.toV2({ status: 200, json: { individuals: [LIVE_INDIVIDUAL] } }, searchReq({ uuid: ['x'] }));
  // dd-MM-yyyy HH:mm:ss - anything else NPEs inside pgr-services
  assert.match(v2.user[0].createdDate, /^\d{2}-\d{2}-\d{4} \d{2}:\d{2}:\d{2}$/);
  assert.match(v2.user[0].lastModifiedDate, /^\d{2}-\d{2}-\d{4} \d{2}:\d{2}:\d{2}$/);
});

test('an individual with no audit block still gets a parseable createdDate rather than null', () => {
  const v2 = user.toV2({ status: 200, json: { individuals: [{ id: 'i-9', givenName: 'Ada', mobileNumber: '1' }] } },
                       searchReq({ uuid: ['i-9'] }));
  assert.match(v2.user[0].createdDate, /^\d{2}-\d{2}-\d{4} \d{2}:\d{2}:\d{2}$/);
});

test('an empty v3 answer is an empty user list - which is how CCRS learns the account is unknown', () => {
  const v2 = user.toV2({ status: 200, json: { totalCount: 0, individuals: [] } },
                       searchReq({ uuid: ['a-2x-uuid-v3-never-heard-of'] }));
  assert.deepStrictEqual(v2.user, []);
});

test('a v3 error becomes a 2.x Errors envelope, NOT an empty user list', () => {
  const v2 = user.toV2(
    { status: 400, json: [{ code: 'VALIDATION_ERROR', message: 'Validation failed' }] },
    searchReq({ uuid: ['a'] })
  );
  assert.ok(!('user' in v2));
  assert.strictEqual(v2.Errors.length, 1);
  assert.strictEqual(v2.Errors[0].code, 'VALIDATION_ERROR');
});

// ---------------------------------------------------------------------------
// create
// ---------------------------------------------------------------------------

const CREATE = { path: '/user/users/_createnovalidate' };
function createReq(u) {
  return {
    requestInfo: { apiId: 'Rainmaker', userInfo: { uuid: 'caller-1', tenantId: 'pg' } },
    user: Object.assign(
      { userName: '9999009999', name: 'Ada Lovelace', type: 'CITIZEN', mobileNumber: '9999009999',
        tenantId: 'pg', active: true, roles: [{ code: 'CITIZEN', name: 'Citizen', tenantId: 'pg' }] },
      u
    ),
  };
}

test('a 2.x user create becomes POST /individual/v3/individuals', () => {
  const out = user.toV3(createReq(), CREATE);
  assert.strictEqual(out.method, 'POST');
  assert.strictEqual(out.path, '/individual/v3/individuals');
  assert.strictEqual(out.headers['X-Tenant-ID'], 'pg');
});

test('the single 2.x name is split into givenName + familyName', () => {
  const out = user.toV3(createReq(), CREATE);
  assert.strictEqual(out.body.givenName, 'Ada');
  assert.strictEqual(out.body.familyName, 'Lovelace');
});

test('gender is INVENTED because v3 demands one and CCRS has no such field', () => {
  const out = user.toV3(createReq(), CREATE);
  assert.strictEqual(out.body.gender, 'OTHER');
});

test('roles, type and userName are stripped - v3 rejects all three as unknown fields', () => {
  const out = user.toV3(createReq(), CREATE);
  assert.ok(!('roles' in out.body));
  assert.ok(!('type' in out.body));
  assert.ok(!('userName' in out.body));
  assert.ok(!('tenantId' in out.body));
  assert.ok(!('active' in out.body));
});

test('a created individual comes back as a CCRS User carrying the roles the caller asked for', () => {
  const created = Object.assign({}, LIVE_INDIVIDUAL, { id: 'new-uuid', givenName: 'Ada', familyName: 'Lovelace' });
  const v2 = user.toV2({ status: 201, json: created }, createReq());
  assert.strictEqual(v2.user.length, 1);
  assert.strictEqual(v2.user[0].uuid, 'new-uuid');
  assert.strictEqual(v2.user[0].name, 'Ada Lovelace');
  assert.deepStrictEqual(v2.user[0].roles, [{ code: 'CITIZEN', name: 'Citizen', tenantId: 'pg' }]);
});

// ---------------------------------------------------------------------------
// update + unknown paths
// ---------------------------------------------------------------------------

test('_updatenovalidate THROWS: v3 PUT needs a `version` the 2.x payload cannot supply', () => {
  assert.throws(
    () => user.toV3(createReq({ uuid: 'i-1' }), { path: '/user/users/_updatenovalidate' }),
    /requires the record `version`/
  );
});

test('a fourth user path THROWS rather than returning a plausible empty result', () => {
  assert.throws(() => user.toV3({ RequestInfo: {} }, { path: '/user/_details' }), /unsupported 2.x user path/);
  assert.throws(() => user.toV3({ RequestInfo: {} }, { path: '/user/oauth/token' }), /unsupported 2.x user path/);
  assert.throws(() => user.toV3({ RequestInfo: {} }, { path: '/user/v1/_search' }), /unsupported 2.x user path/);
});

// EGOV_USER_HOST ends in a slash and egov.user.search.path starts with one, so
// the URI pgr-services concatenates is literally ".../8107//user/_search". The
// egov-user-proxy access log proves Spring normalises it before it leaves the
// JVM - every logged line reads "POST /user/_search" with ONE slash - so the
// shim's router never sees the doubled form. (It would not match it if it did:
// router.resolveEntry matches on a path-segment boundary, and "//user/_search"
// answers 502 SHIM_UNMAPPED. Verified against the running shim. That is the
// correct, loud behaviour for a path CCRS does not send.) The translator
// itself is indifferent to the spelling:
test('the translator dispatches on the sub-path regardless of leading slashes', () => {
  const out = user.toV3(searchReq({ uuid: ['a'] }), { path: '//user/_search' });
  assert.ok(out.path.includes('/individual/v3/individuals'));
});
