// test/workflow.test.js
//
// GAP_LOG is redirected to a temp dir first, so running the suite never writes
// to the real /opt/hybrid-shim/gaps.jsonl (same convention as idgen/boundary).
const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'workflow-gaps-'));
process.env.GAP_LOG = path.join(TMP, 'gaps.jsonl');

const test = require('node:test');
const assert = require('node:assert');
const wf = require('../src/translators/workflow');

// ---------------------------------------------------------------- helpers

// Mimics what server.js builds: the parsed 2.x body plus a non-enumerable
// __shim carrying the transport details.
function req(body, { method = 'POST', path: p = '', query = {} } = {}) {
  Object.defineProperty(body, '__shim', {
    value: { method, path: p, query, headers: {}, rawBody: JSON.stringify(body) },
    enumerable: false,
    configurable: true,
  });
  Object.defineProperty(body, '__query', { value: query, enumerable: false, configurable: true });
  return body;
}

const TRANSITION = '/egov-workflow-v2/egov-wf/process/_transition';
const BS_SEARCH = '/egov-workflow-v2/egov-wf/businessservice/_search';
const PROC_SEARCH = '/egov-workflow-v2/egov-wf/process/_search';

function gaps() {
  if (!fs.existsSync(process.env.GAP_LOG)) return [];
  return fs
    .readFileSync(process.env.GAP_LOG, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l));
}

function lastGapFor(field) {
  return gaps().filter((g) => g.field === field).pop();
}

// ================================================================
// The two contract tests from the task brief.
//
// The bodies and every assertion are the brief's, unchanged. The ONE
// difference is that each request is wrapped in req(..., {path}) so it carries
// the __shim transport context server.js always supplies. The brief's literals
// relied on a body-shape fallback inside toV3/toV2 for the case where __shim
// was absent - and that fallback was removed, because it meant an
// unrecognised live call would have been dispatched as a businessservice
// search and answered a plausible empty envelope instead of throwing. Keeping
// a test affordance alive in production code at that price was the wrong
// trade; the tests construct the context the real caller always has.
// ================================================================

test('maps a 2.x transition to the v3 transition call', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'mz' } },
    ProcessInstances: [{ tenantId: 'mz', businessService: 'PGR', businessId: 'PGR-123',
                         action: 'ASSIGN', moduleName: 'PGR', assignes: [{ uuid: 'emp-1' }] }] },
    { path: TRANSITION });
  const out = wf.toV3(r);
  assert.strictEqual(out.method, 'POST');
  assert.strictEqual(out.path, '/workflow/v3/transition');
  assert.strictEqual(out.body.processCode, 'PGR');
  assert.strictEqual(out.body.action, 'ASSIGN');
});

test('rebuilds the 2.x ProcessInstances envelope', () => {
  const v2 = wf.toV2({ state: { code: 'PENDINGFORASSIGNMENT' } },
    req({ ProcessInstances: [{ businessId: 'PGR-123' }] }, { path: TRANSITION }));
  assert.strictEqual(v2.ProcessInstances[0].state.code, 'PENDINGFORASSIGNMENT');
});

// ================================================================
// 1. /process/_transition
// ================================================================

test('transition: tenant and user ride in headers, never in the body', () => {
  const r = req({
    RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg', roles: [{ code: 'GRO' }] } },
    ProcessInstances: [{ tenantId: 'pg', businessService: 'PGR', businessId: 'PG-PGR-1', action: 'ASSIGN' }],
  }, { path: TRANSITION });
  const out = wf.toV3(r);
  assert.strictEqual(out.headers['X-Tenant-ID'], 'pg');
  assert.strictEqual(out.headers['X-User-ID'], 'u-1');
  assert.strictEqual(out.body.tenantId, undefined);
  assert.strictEqual(out.body.entityId, 'PG-PGR-1');
});

test('transition: roles go out as an unsigned realm_access bearer, because v3 reads them nowhere else', () => {
  const r = req({
    RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg', roles: [{ code: 'GRO' }, { code: 'PGR_LME' }] } },
    ProcessInstances: [{ tenantId: 'pg', businessService: 'PGR', businessId: 'PG-PGR-1', action: 'RESOLVE' }],
  }, { path: TRANSITION });
  const out = wf.toV3(r);
  const [scheme, token] = out.headers.Authorization.split(' ');
  assert.strictEqual(scheme, 'Bearer');
  const [h, p, sig] = token.split('.');
  assert.deepStrictEqual(JSON.parse(Buffer.from(h, 'base64url').toString()).alg, 'none');
  assert.deepStrictEqual(JSON.parse(Buffer.from(p, 'base64url').toString()).realm_access.roles,
    ['GRO', 'PGR_LME']);
  assert.strictEqual(sig, '');
  assert.strictEqual(lastGapFor('Authorization').action, 'faked');
});

test('transition: no roles means no bearer, and the loss is logged', () => {
  const r = req({
    RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } },
    ProcessInstances: [{ tenantId: 'pg', businessService: 'PGR', businessId: 'PG-PGR-1', action: 'ASSIGN' }],
  }, { path: TRANSITION });
  const out = wf.toV3(r);
  assert.strictEqual(out.headers.Authorization, undefined);
  assert.strictEqual(lastGapFor('RequestInfo.userInfo.roles').action, 'dropped');
});

test('transition: assignes become bare uuid strings', () => {
  const r = req({
    RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg', roles: [{ code: 'GRO' }] } },
    ProcessInstances: [{ tenantId: 'pg', businessService: 'PGR', businessId: 'PG-PGR-1', action: 'ASSIGN',
                         assignes: [{ uuid: 'emp-1', name: 'Someone', roles: [{ code: 'PGR_LME' }] }] }],
  }, { path: TRANSITION });
  const out = wf.toV3(r);
  assert.deepStrictEqual(out.body.assignees, ['emp-1']);
  assert.strictEqual(lastGapFor('ProcessInstances[0].assignes[]').action, 'dropped');
});

test('transition: a batch of ProcessInstances is a hard error, never a silent truncation', () => {
  const r = req({
    RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } },
    ProcessInstances: [
      { businessService: 'PGR', businessId: 'A', action: 'ASSIGN' },
      { businessService: 'PGR', businessId: 'B', action: 'ASSIGN' },
    ],
  }, { path: TRANSITION });
  assert.throws(() => wf.toV3(r), /exactly one/);
  assert.strictEqual(lastGapFor('ProcessInstances[]').action, 'unmapped');
});

test('transition: the v3 reply is rebuilt as a 2.x ProcessInstances envelope', () => {
  const r = req({
    RequestInfo: { apiId: 'Rainmaker', ver: '.01', msgId: 'm1', userInfo: { uuid: 'u-1', tenantId: 'pg' } },
    ProcessInstances: [{ tenantId: 'pg', businessService: 'PGR', businessId: 'PG-PGR-1',
                         action: 'ASSIGN', moduleName: 'pgr-services' }],
  }, { path: TRANSITION });
  const v3 = { status: 200, json: {
    id: 'pi-1', processCode: 'PGR', entityId: 'PG-PGR-1', action: 'ASSIGN', comment: 'c',
    assignees: ['emp-1'], assigner: '', currentState: 'PENDINGATLME',
    stateSla: 111, processSla: 222,
    auditDetail: { createdBy: 'u-1', createdTime: 1, modifiedBy: 'u-1', modifiedTime: 2 },
  } };
  const out = wf.toV2(v3, r);

  assert.strictEqual(out.ResponseInfo.apiId, 'Rainmaker');
  assert.strictEqual(out.ResponseInfo.status, 'successful');
  assert.strictEqual(out.ProcessInstances.length, 1);

  const pi = out.ProcessInstances[0];
  assert.strictEqual(pi.businessId, 'PG-PGR-1');
  assert.strictEqual(pi.businessService, 'PGR');
  assert.strictEqual(pi.tenantId, 'pg');
  // CCRS reads exactly this to set the complaint's applicationStatus.
  assert.strictEqual(pi.state.applicationStatus, 'PENDINGATLME');
  assert.strictEqual(pi.state.state, 'PENDINGATLME');
  // moduleName has no v3 field; it is echoed from the request.
  assert.strictEqual(pi.moduleName, 'pgr-services');
  assert.deepStrictEqual(pi.assignes, [{ uuid: 'emp-1' }]);
  // v3 blanks assigner; fall back to the acting user.
  assert.deepStrictEqual(pi.assigner, { uuid: 'u-1' });
  assert.deepStrictEqual(pi.auditDetails,
    { createdBy: 'u-1', lastModifiedBy: 'u-1', createdTime: 1, lastModifiedTime: 2 });
  assert.strictEqual(pi.businesssServiceSla, 222);
  // The whole State definition is unreachable from a v3 transition reply.
  assert.strictEqual(pi.state.actions, null);
  assert.strictEqual(pi.state.uuid, null);
  assert.strictEqual(pi.state.isTerminateState, null);
  assert.strictEqual(lastGapFor('ProcessInstances[].state').action, 'dropped');
});

// ================================================================
// 2. /businessservice/_search
// ================================================================

test('businessservice search: query criteria become the v3 definition filter', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg', roles: [{ code: 'GRO' }] } } },
    { path: BS_SEARCH, query: { tenantId: 'pg', businessServices: 'PGR' } });
  const out = wf.toV3(r);
  assert.strictEqual(out.method, 'GET');
  assert.ok(out.path.startsWith('/workflow/v3/process/definition?'));
  const q = new URL(out.path, 'http://x').searchParams;
  assert.strictEqual(q.get('processCode'), 'PGR');
  assert.strictEqual(q.get('size'), '100');
  assert.strictEqual(q.get('tenantId'), null);
  assert.strictEqual(out.headers['X-Tenant-ID'], 'pg');
});

test('businessservice search: several services cannot be served and say so', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: BS_SEARCH, query: { tenantId: 'pg', businessServices: 'PGR,TL' } });
  wf.toV3(r);
  assert.strictEqual(lastGapFor('businessServices').action, 'unmapped');
});

test('businessservice search: a v3 definition rebuilds the 2.x BusinessService', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: BS_SEARCH, query: { tenantId: 'pg', businessServices: 'PGR' } });
  const v3 = { status: 200, json: { definitions: [{
    id: 'proc-1', code: 'PGR', name: 'PGR', version: '1.0', sla: 432000000,
    additionalDetails: { business: 'pgr-services' },
    auditDetail: { createdBy: 'shim', createdTime: 1, modifiedBy: 'shim', modifiedTime: 2 },
    states: [
      { id: 's0', code: 'START', name: 'Start', type: 'INITIAL', isInitial: true,
        additionalDetails: { twoXIsStateUpdatable: true, twoXDocUploadRequired: false },
        actions: [{ id: 'a0', code: 'APPLY', nextState: 's1', roles: ['CITIZEN', 'CSR'] }] },
      { id: 's1', code: 'PENDINGFORASSIGNMENT', name: 'P', type: 'INTERMEDIATE',
        additionalDetails: { twoXIsStateUpdatable: false, twoXDocUploadRequired: false },
        actions: [{ id: 'a1', code: 'ASSIGN', nextState: 's2', roles: ['GRO', 'PGR_VIEWER'] }] },
      { id: 's2', code: 'RESOLVED', name: 'R', type: 'INTERMEDIATE', sla: 300000,
        additionalDetails: { twoXIsTerminateState: true }, actions: [] },
    ],
  }] } };
  const out = wf.toV2(v3, r);

  assert.strictEqual(out.BusinessServices.length, 1);
  const bs = out.BusinessServices[0];
  // This is the ONE field WorkflowService.getBusinessService actually reads.
  assert.strictEqual(bs.businessService, 'PGR');
  assert.strictEqual(bs.tenantId, 'pg');
  assert.strictEqual(bs.uuid, 'proc-1');
  assert.strictEqual(bs.businessServiceSla, 432000000);
  assert.strictEqual(bs.business, 'pgr-services');
  // 2.x names its start state null; v3 forbids an empty code.
  assert.strictEqual(bs.states[0].state, null);
  assert.strictEqual(bs.states[0].isStartState, true);
  assert.strictEqual(bs.states[0].isStateUpdatable, true);
  assert.strictEqual(bs.states[1].state, 'PENDINGFORASSIGNMENT');
  assert.strictEqual(bs.states[1].applicationStatus, 'PENDINGFORASSIGNMENT');
  assert.strictEqual(bs.states[1].actions[0].action, 'ASSIGN');
  assert.deepStrictEqual(bs.states[1].actions[0].roles, ['GRO', 'PGR_VIEWER']);
  assert.strictEqual(bs.states[1].actions[0].uuid, 'a1');
  assert.strictEqual(bs.states[1].actions[0].nextState, 's2');
  // 2.x soft-deletes actions; v3 has no flag for it.
  assert.strictEqual(bs.states[1].actions[0].active, null);
  // v3 as deployed collapses TERMINAL_* to INTERMEDIATE, so this is a
  // shim-registered annotation, not a v3 fact - and it says so.
  assert.strictEqual(bs.states[2].isTerminateState, true);
  assert.strictEqual(lastGapFor('BusinessServices[].states[].isTerminateState').action, 'faked');
});

test('businessservice search: on the LIVE v3 shape every 2.x-only field is null, not invented', () => {
  // What the deployed service actually returns: no additionalDetails anywhere
  // (it accepts the field and discards it) and TERMINAL_* rewritten to
  // INTERMEDIATE. Nothing may be conjured out of that.
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: BS_SEARCH, query: { tenantId: 'pg', businessServices: 'PGR' } });
  const v3 = { status: 200, json: { definitions: [{
    id: 'proc-1', code: 'PGR', name: 'PGR', version: '1.0', sla: 432000000,
    auditDetail: { createdBy: 'shim', createdTime: 1, modifiedBy: 'shim', modifiedTime: 2 },
    states: [
      { id: 's0', code: 'START', name: 'Start', type: 'INITIAL', isInitial: true,
        actions: [{ id: 'a0', code: 'APPLY', nextState: 's1', roles: ['CITIZEN', 'CSR'] }] },
      { id: 's2', code: 'RESOLVED', name: 'R', type: 'INTERMEDIATE', sla: 300000, actions: [] },
    ],
  }] } };
  const bs = wf.toV2(v3, r).BusinessServices[0];
  assert.strictEqual(bs.businessService, 'PGR');
  assert.strictEqual(bs.business, null);
  assert.strictEqual(bs.getUri, null);
  assert.strictEqual(bs.postUri, null);
  assert.strictEqual(bs.states[0].state, null);
  assert.strictEqual(bs.states[0].isStartState, true);
  assert.strictEqual(bs.states[0].docUploadRequired, null);
  assert.strictEqual(bs.states[0].isStateUpdatable, null);
  // The real answer: v3 as deployed cannot say whether RESOLVED is terminal.
  assert.strictEqual(bs.states[1].isTerminateState, null);
});

// ================================================================
// 3. /process/_search, all three query shapes
// ================================================================

test('process search by businessIds: processCode is invented, and recorded as invented', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg', roles: [{ code: 'GRO' }] } } },
    { path: PROC_SEARCH, query: { tenantId: 'pg', businessIds: 'PG-PGR-1' } });
  const out = wf.toV3(r);
  assert.strictEqual(out.method, 'GET');
  const q = new URL(out.path, 'http://x').searchParams;
  assert.strictEqual(q.get('entityId'), 'PG-PGR-1');
  assert.strictEqual(q.get('processCode'), 'PGR');
  assert.strictEqual(q.get('history'), null);
  const g = lastGapFor('processCode');
  assert.strictEqual(g.action, 'defaulted');
  assert.match(g.detail, /INVENTED VALUE/);
});

test('process search: several businessIds cannot be served and say so', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: PROC_SEARCH, query: { tenantId: 'pg', businessIds: 'PG-PGR-1,PG-PGR-2' } });
  const out = wf.toV3(r);
  // Forwarded verbatim: v3 answers an empty list and CCRS throws
  // WORKFLOW_NOT_FOUND. Loud beats a half-filled inbox.
  assert.strictEqual(new URL(out.path, 'http://x').searchParams.get('entityId'), 'PG-PGR-1,PG-PGR-2');
  assert.strictEqual(lastGapFor('businessIds').action, 'unmapped');
});

test('process search with history=true is carried across, cap and all', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: PROC_SEARCH, query: { tenantId: 'pg', businessIds: 'PG-PGR-1', history: 'true' } });
  const out = wf.toV3(r);
  const q = new URL(out.path, 'http://x').searchParams;
  assert.strictEqual(q.get('history'), 'true');
  assert.strictEqual(q.get('size'), '100');
  assert.strictEqual(lastGapFor('history').action, 'defaulted');
});

test('inbox search by assignee keeps the business service it was given', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: PROC_SEARCH, query: { tenantId: 'pg', businessService: 'PGR', assignee: 'emp-1' } });
  const out = wf.toV3(r);
  const q = new URL(out.path, 'http://x').searchParams;
  assert.strictEqual(q.get('assignee'), 'emp-1');
  assert.strictEqual(q.get('processCode'), 'PGR');
  assert.strictEqual(q.get('entityId'), null);
});

test('process search: v3 rows rebuild the 2.x ProcessInstances envelope', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: PROC_SEARCH, query: { tenantId: 'pg', businessIds: 'PG-PGR-1', history: 'true' } });
  const v3 = { status: 200, json: { totalCount: 2, page: 0, size: 20, processInstances: [
    { id: 'pi-1', processCode: 'PGR', entityId: 'PG-PGR-1', action: 'APPLY',
      comment: 'c1', currentState: 'PENDINGFORASSIGNMENT', escalated: false,
      auditDetail: { createdBy: 'cit', createdTime: 1, modifiedBy: 'cit', modifiedTime: 1 } },
    { id: 'pi-2', processCode: 'PGR', entityId: 'PG-PGR-1', action: 'ASSIGN',
      comment: 'c2', assignees: ['emp-1'], currentState: 'PENDINGATLME', isLatest: true,
      auditDetail: { createdBy: 'gro', createdTime: 2, modifiedBy: 'gro', modifiedTime: 2 } },
  ] } };
  const out = wf.toV2(v3, r);
  assert.strictEqual(out.ProcessInstances.length, 2);
  // Oldest first, as both stacks order history.
  assert.strictEqual(out.ProcessInstances[0].action, 'APPLY');
  assert.strictEqual(out.ProcessInstances[1].action, 'ASSIGN');
  assert.strictEqual(out.ProcessInstances[1].businessId, 'PG-PGR-1');
  assert.strictEqual(out.ProcessInstances[1].state.applicationStatus, 'PENDINGATLME');
  assert.deepStrictEqual(out.ProcessInstances[1].assignes, [{ uuid: 'emp-1' }]);
  // 2.x computes the role-filtered next actions; v3 reports none.
  assert.strictEqual(out.ProcessInstances[1].nextActions, undefined);
  assert.strictEqual(lastGapFor('ProcessInstances[]').action, 'defaulted');
});

test('process search: an empty v3 result stays empty, and is never faked into a hit', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: PROC_SEARCH, query: { tenantId: 'pg', businessService: 'PGR', assignee: 'nobody' } });
  const out = wf.toV2({ status: 200, json: { totalCount: 0, processInstances: [] } }, r);
  assert.deepStrictEqual(out.ProcessInstances, []);
});

// ================================================================
// dispatch
// ================================================================

test('a fourth workflow path throws loudly instead of guessing an envelope', () => {
  const r = req({ RequestInfo: {} }, { path: '/egov-workflow-v2/egov-wf/businessservice/_create' });
  assert.throws(() => wf.toV3(r), /unsupported 2\.x workflow path/);
  const g = lastGapFor('/egov-workflow-v2/egov-wf/businessservice/_create');
  assert.strictEqual(g.action, 'unmapped');
  assert.throws(() => wf.toV2({ status: 200, json: {} }, r), /unsupported 2\.x workflow path/);
});

test('the translator names itself, so a failure is attributed to egov-workflow-v2', () => {
  assert.strictEqual(wf.dep, 'egov-workflow-v2');
});

// ================================================================
// Regression: silent truncation, and the removed body-shape fallback
// ================================================================

test('process search: a short v3 page is recorded as truncation, not passed off as the whole list', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: PROC_SEARCH, query: { tenantId: 'pg', businessService: 'PGR', assignee: 'emp-1' } });
  const rows = Array.from({ length: 100 }, (_, i) => ({
    id: `pi-${i}`, processCode: 'PGR', entityId: `PG-PGR-${i}`, action: 'ASSIGN',
    currentState: 'PENDINGATLME',
  }));
  const out = wf.toV2({ status: 200, json: { totalCount: 137, page: 0, size: 100, processInstances: rows } }, r);

  // Nothing is padded or invented - the caller gets exactly what v3 sent.
  assert.strictEqual(out.ProcessInstances.length, 100);

  const g = lastGapFor('totalCount');
  assert.strictEqual(g.action, 'dropped');
  assert.match(g.detail, /totalCount=137/);
  assert.match(g.detail, /only 100 row\(s\)/);
  assert.match(g.detail, /37 row\(s\) were dropped/);
  // The entry names the 2.x path it came from, so the gap log says WHICH call lost rows.
  assert.match(g.detail, /process\/_search/);
});

test('process search: a complete v3 page records no truncation', () => {
  const before = gaps().length;
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: PROC_SEARCH, query: { tenantId: 'pg', businessIds: 'PG-PGR-1' } });
  const out = wf.toV2({ status: 200, json: { totalCount: 1, page: 0, size: 100, processInstances: [
    { id: 'pi-1', processCode: 'PGR', entityId: 'PG-PGR-1', action: 'APPLY',
      currentState: 'PENDINGFORASSIGNMENT' },
  ] } }, r);
  assert.strictEqual(out.ProcessInstances.length, 1);
  assert.strictEqual(gaps().slice(before).some((g) => g.field === 'totalCount'), false);
});

test('process search: an empty result with totalCount 0 records no truncation', () => {
  const before = gaps().length;
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: PROC_SEARCH, query: { tenantId: 'pg', businessService: 'PGR', assignee: 'nobody' } });
  wf.toV2({ status: 200, json: { totalCount: 0, page: 0, size: 100, processInstances: [] } }, r);
  assert.strictEqual(gaps().slice(before).some((g) => g.field === 'totalCount'), false);
});

test('businessservice search: a short definition page is recorded too', () => {
  const r = req({ RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } },
    { path: BS_SEARCH, query: { tenantId: 'pg' } });
  wf.toV2({ status: 200, json: { totalCount: 4, page: 0, size: 100, definitions: [
    { id: 'p1', code: 'PGR', name: 'PGR', states: [] },
  ] } }, r);
  const g = lastGapFor('totalCount');
  assert.match(g.detail, /totalCount=4/);
  assert.match(g.detail, /businessservice\/_search/);
});

test('a request with no __shim throws instead of being guessed from its body shape', () => {
  // The removed fallback would have served this as a businessservice search
  // and answered {BusinessServices: []} - HTTP 200, plausible, wrong.
  const bare = { RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } } };
  assert.throws(() => wf.toV3(bare), /carried no __shim/);
  assert.throws(() => wf.toV2({ status: 200, json: {} }, bare), /carried no __shim/);
  const g = lastGapFor('(no path: req2x carried no __shim)');
  assert.strictEqual(g.action, 'unmapped');
});

test('a transition-shaped body with no __shim also throws, rather than being recognised', () => {
  const bare = { RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } },
    ProcessInstances: [{ businessService: 'PGR', businessId: 'X', action: 'ASSIGN' }] };
  assert.throws(() => wf.toV3(bare), /carried no __shim/);
  assert.throws(() => wf.toV2({ status: 200, json: {} }, bare), /carried no __shim/);
});

test('undefined and null are rejected the same way', () => {
  assert.throws(() => wf.toV3(undefined), /carried no __shim/);
  assert.throws(() => wf.toV3(null), /carried no __shim/);
});
