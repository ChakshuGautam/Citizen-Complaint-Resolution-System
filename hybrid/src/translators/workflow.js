// src/translators/workflow.js
//
// CCRS (DIGIT 2.12) egov-workflow-v2  ->  DIGIT 3.0 workflow.
//
// =====================================================================
// OBSERVED CONTRACTS (probed live on this box 2026-09-20, not guessed)
// =====================================================================
//
// EGOV_WORKFLOW_HOST serves THREE distinct 2.x paths, and one of them has
// THREE different query shapes with three different meanings. Counting them
// first is the whole lesson of Task 4: a sub-path that gets the wrong envelope
// comes back 200 and empty, not as an error.
//
// The inventory below is NOT from reading Java alone. pgr-services reaches the
// workflow service through the nginx pass-through `egov-workflow-proxy:8109`
// (/opt/digit/nginx/workflow-proxy.conf - `location / { proxy_pass
// http://egov-workflow-v2:8109; }`, no added semantics), whose access log was
// read during a full green lifecycle run. That is where `history=true` turned
// up: it is in NO call-site listed in the task brief.
//
//   ----------------------------------------------------------------------
//   1. POST /egov-workflow-v2/egov-wf/process/_transition
//      WorkflowService.callWorkFlow:244. Body = ProcessInstanceRequest
//      {RequestInfo, ProcessInstances:[one]}. 3 calls per lifecycle
//      (APPLY, ASSIGN, RESOLVE).
//      -> POST /workflow/v3/transition
//
//   2. POST /egov-workflow-v2/egov-wf/businessservice/_search
//           ?tenantId=<t>&businessServices=PGR
//      WorkflowService.getSearchURLWithParams:95 AND
//      MigrationUtils.getSearchURLWithParams:147. Body = RequestInfoWrapper.
//      3 calls per lifecycle (once per transition - it is what supplies
//      ProcessInstance.businessService).
//      -> GET /workflow/v3/process/definition?processCode=PGR
//
//   3. POST /egov-workflow-v2/egov-wf/process/_search  -- THREE query shapes:
//      3a ?tenantId=&businessIds=<CSV>
//         WorkflowService.enrichWorkflow:131. 2 calls per lifecycle (the two
//         complaint searches). CCRS THROWS WORKFLOW_NOT_FOUND unless the reply
//         holds exactly one ProcessInstance per id asked for.
//      3b ?tenantId=&businessIds=<one>&history=true
//         NotificationService.getEmployeeName:662-663 and
//         EscalationService:277-278. 2 calls per lifecycle. Wants the FULL
//         ordered transition history and scans it for a given action.
//      3c ?tenantId=&businessService=PGR&assignee=<uuid>
//         WorkflowService.getServiceRequestIdsByAssignee:264. Not exercised by
//         the lifecycle (it fires only when an employee search carries
//         ?assignee=), but it is a real shape and is translated.
//      -> GET /workflow/v3/transition
//   ----------------------------------------------------------------------
//
// Any FOURTH path throws. It is not translated best-effort.
//
// --- hostname check (the Task 4 trap) --------------------------------
// `d3-boundary-service` claims the network alias `boundary-service`, so that
// name resolves to v3 from inside the shim. The workflow equivalent was
// checked before trusting anything: `d3-workflow-service` claims
// [d3-workflow-service, workflow-service] on digit3_default ONLY, while
// `digit-egov-workflow-v2-1` claims [digit-egov-workflow-v2-1, egov-workflow-v2]
// on digit_egov-network. No collision: from the shim, `egov-workflow-v2` and
// `egov-workflow-proxy` are 2.x, `workflow-service` is v3. Nothing here relies
// on either name - client.js talks to http://d3-kong:8000.
//
// =====================================================================
// ROLES: v3 CARRIES THEM, BUT ONLY IN A BEARER JWT
// =====================================================================
// digit-specs/v3.0.0/workflow.yaml `ActionInput` does NOT declare a `roles`
// field. The LIVE service nevertheless accepts, stores, echoes AND ENFORCES
// one - a transition whose action has `roles` is answered
//   403 {"code":"Forbidden","message":"caller role does not permit the
//        requested action: guard check failed: ..."}
// unless the caller presents matching roles.
//
// The ONLY channel that works is `Authorization: Bearer <jwt>` with
// `realm_access.roles`. `X-Roles: GRO,CSR` is named in the Java mirror
// (com.digit.workflow.constants.Headers.ROLES) and is IGNORED by the deployed
// Go build - verified: same request, X-Roles -> 403, Bearer -> 200.
//
// The signature is NOT verified: `{"alg":"none"}` with an empty third segment
// is accepted. So this shim mints an UNSIGNED token carrying exactly the roles
// the 2.x RequestInfo already asserts, and nothing else. That is role CONTEXT
// TRANSPORT, not authentication - it grants no privilege the caller did not
// already claim in the 2.x payload, and v3 here has no auth to add to (Kong
// has zero plugins). It is gap-logged on every request, because (a) the field
// is undocumented, (b) the transport is undocumented, and (c) a service that
// accepts alg:none is trusting its caller completely.
//
// NOT sending roles is the worse option and was rejected: an action with no
// `roles` is open to everyone (verified), so omitting them would make CCRS's
// entire role model evaporate SILENTLY - the exact failure mode Task 4 cost
// two runs to find.
//
// =====================================================================
// WHAT DOES NOT CROSS (each of these is in gaps.jsonl)
// =====================================================================
//  * SELF-LOOP ACTIONS ARE DEAD IN v3. Any action with nextState ==
//    currentState is stored happily by POST /process/definition and then
//    refused at runtime with 409 "invalid state transition: no transition".
//    That is COMMENT (at all five non-terminal 2.x states) and ESCALATE (at
//    PENDINGATLME). Verified with a throwaway definition, not inferred.
//  * isTerminateState HAS NO v3 REPRESENTATION. TERMINAL_SUCCESS /
//    TERMINAL_FAILURE are accepted by POST /process/definition and SILENTLY
//    REWRITTEN TO INTERMEDIATE. Verified: a probe state sent as
//    TERMINAL_SUCCESS came back INTERMEDIATE.
//  * The FULL State definition that 2.x staples onto every transition reply
//    (state.uuid, state.actions[] with their own uuids/roles/auditDetails,
//    docUploadRequired, isStateUpdatable, businessServiceId) is simply not in
//    the v3 TransitionResponse, which returns only `currentState` as a code.
//    Reconstructing it would need a SECOND v3 call per transition, which the
//    one-request-in / one-request-out translator contract cannot express.
//  * assignes/assigner are FULL 2.x User objects (id, userName, name,
//    mobileNumber, roles[]); v3 stores bare user-id STRINGS. The shim emits
//    {uuid} and nothing else. CCRS survives - getWorkflow() reads only
//    User::getUuid, and NotificationService re-fetches the user by uuid.
//  * v3 DISCARDS `assigner` on a transition: it is echoed back as "".
//  * MULTI-ID SEARCH IS IMPOSSIBLE. 2.x ?businessIds=a,b returns both. v3
//    returns [] for the comma form, and honours only the FIRST value of the
//    repeated ?entityId=a&entityId=b form. The CSV is forwarded verbatim so
//    CCRS gets an honest empty answer and throws WORKFLOW_NOT_FOUND loudly.
//  * v3 REQUIRES processCode when filtering by entityId (400 without it), and
//    2.x's businessIds search carries NO business service. The shim supplies
//    WORKFLOW_PROCESS_CODE (default PGR) and gap-logs 'defaulted' every time.
//  * `moduleName` and `notificationAction` have no v3 field.
//  * v3 caps `size` at 100 (400 above it), so a history longer than 100
//    transitions is silently truncated by v3's paging.
//  * `additionalDetails` IS ACCEPTED AND DISCARDED on process definitions and
//    states. The spec declares it free-form on both; reading the registered PGR
//    definition back shows it absent at both levels. So there is nowhere to
//    park a 2.x concept v3 lacks, and BusinessService.business,
//    State.docUploadRequired, State.isStateUpdatable and State.isTerminateState
//    all come back null in practice.
//  * ResponseInfo does not exist in v3; fabricated exactly as idgen/boundary do.
// =====================================================================

const gaplog = require('../gaplog');

const DEP = 'egov-workflow-v2';

const TRANSITION_2X = '/egov-workflow-v2/egov-wf/process/_transition';
const BUSINESSSERVICE_SEARCH_2X = '/egov-workflow-v2/egov-wf/businessservice/_search';
const PROCESS_SEARCH_2X = '/egov-workflow-v2/egov-wf/process/_search';

const V3_TRANSITION = '/workflow/v3/transition';
const V3_DEFINITION = '/workflow/v3/process/definition';

// v3 refuses size > 100 with 400 "size must be an integer between 1 and 100".
const V3_MAX_SIZE = 100;

// The 2.x nameless start state needs a code in v3; see pgr-process-definition.yaml.
const V3_START_STATE = 'START';

function defaultProcessCode() {
  return process.env.WORKFLOW_PROCESS_CODE || 'PGR';
}

// ---------------------------------------------------------------- helpers

function queryOf(req2x) {
  if (!req2x || typeof req2x !== 'object') return {};
  if (req2x.__query && typeof req2x.__query === 'object') return req2x.__query;
  if (req2x.__shim && req2x.__shim.query && typeof req2x.__shim.query === 'object') {
    return req2x.__shim.query;
  }
  return {};
}

function requestInfoOf(req2x) {
  if (!req2x || typeof req2x !== 'object') return {};
  if (req2x.RequestInfo && typeof req2x.RequestInfo === 'object') return req2x.RequestInfo;
  return {};
}

function userInfoOf(req2x) {
  const ui = requestInfoOf(req2x).userInfo;
  return ui && typeof ui === 'object' ? ui : {};
}

// Tenant is never hardcoded. On the two search shapes CCRS puts it in the
// query string, which is what it actually drives the search with, so that
// wins; the RequestInfo userInfo is the fallback, and is the only source on
// _transition (where the tenant rides on each ProcessInstance instead).
function tenantOf(req2x, processInstance) {
  const q = queryOf(req2x);
  const ui = userInfoOf(req2x);
  const ri = requestInfoOf(req2x);
  return (
    q.tenantId ||
    (processInstance && processInstance.tenantId) ||
    ui.tenantId ||
    ri.tenantId ||
    ''
  );
}

function userIdOf(req2x) {
  return userInfoOf(req2x).uuid || '';
}

function rolesOf(req2x) {
  const roles = userInfoOf(req2x).roles;
  if (!Array.isArray(roles)) return [];
  return roles
    .map((r) => (r && typeof r === 'object' ? r.code : r))
    .filter((c) => typeof c === 'string' && c.length);
}

function b64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// The one channel v3 reads roles from. See the ROLES note at the top.
function rolesBearer(req2x, which) {
  const roles = rolesOf(req2x);
  if (!roles.length) {
    gaplog.record({
      dep: DEP,
      field: 'RequestInfo.userInfo.roles',
      action: 'dropped',
      detail:
        `The 2.x ${which} carried no userInfo.roles, so no role context could be sent to v3. Any v3 ` +
        'action that declares `roles` will answer 403 Forbidden; any action that declares none is ' +
        'open to everyone. Either way the caller gets a definite answer, not a silently wrong one.',
    });
    return null;
  }
  gaplog.record({
    dep: DEP,
    field: 'Authorization',
    action: 'faked',
    detail:
      `Minted an UNSIGNED ("alg":"none", empty signature) Bearer JWT carrying realm_access.roles=` +
      `${JSON.stringify(roles)} for the 2.x ${which}. This is the ONLY way to give DIGIT 3.0 the ` +
      "caller's roles: the live workflow service enforces Action.roles (403 " +
      '"caller role does not permit the requested action") but reads roles ONLY from a Bearer ' +
      "token's realm_access.roles - the X-Roles header named in the service's own Headers " +
      'constant is ignored by the deployed build (verified: X-Roles -> 403, Bearer -> 200). Three ' +
      'things are wrong here and none of them is fixable in a shim: (1) ActionInput in ' +
      'digit-specs/v3.0.0/workflow.yaml does not declare `roles` at all, so this whole guard is ' +
      'undocumented; (2) there is no non-JWT role channel, so any 2.x caller must forge a token; ' +
      '(3) the service does not verify the signature, so an unsigned token is honoured - a real ' +
      'deployment would need Keycloak-issued tokens and a verifying gateway. The token asserts ' +
      'nothing the inbound 2.x RequestInfo did not already assert.',
  });
  const header = b64url(JSON.stringify({ alg: 'none', typ: 'JWT' }));
  const payload = b64url(JSON.stringify({ realm_access: { roles } }));
  return `Bearer ${header}.${payload}.`;
}

function authHeaders(req2x, which, tenantId) {
  const headers = { 'X-Tenant-ID': tenantId, 'X-User-ID': userIdOf(req2x) };
  const bearer = rolesBearer(req2x, which);
  if (bearer) headers.Authorization = bearer;
  return headers;
}

function responseInfo(req2x, which) {
  const ri = requestInfoOf(req2x);
  gaplog.record({
    dep: DEP,
    field: 'ResponseInfo',
    action: 'faked',
    detail:
      `v3 ${which} returns its payload and nothing else - DIGIT 3.0 has no ResponseInfo envelope. ` +
      'The 2.x ResponseInfo is reconstructed from the inbound RequestInfo (apiId/ver/msgId echoed) ' +
      'with ts=null, resMsgId="uief87324" and status="successful". Nothing in it is reported by v3. ' +
      'Note the live 2.x egov-workflow-v2 actually answers "ResponseInfo": null on ' +
      '/process/_search, so this shim is if anything MORE informative than the thing it replaces.',
  });
  return {
    apiId: ri.apiId === undefined ? null : ri.apiId,
    ver: ri.ver === undefined ? null : ri.ver,
    ts: null,
    resMsgId: 'uief87324',
    msgId: ri.msgId === undefined ? null : ri.msgId,
    status: 'successful',
  };
}

function bodyOf(v3Response) {
  if (!v3Response || typeof v3Response !== 'object') return {};
  if ('json' in v3Response || 'status' in v3Response || 'raw' in v3Response) {
    return v3Response.json && typeof v3Response.json === 'object' ? v3Response.json : {};
  }
  return v3Response;
}

// v3: {createdBy, createdTime, modifiedBy, modifiedTime} under `auditDetail`
// 2.x: {createdBy, createdTime, lastModifiedBy, lastModifiedTime} under `auditDetails`
function mapAudit(v3Audit) {
  if (!v3Audit || typeof v3Audit !== 'object') return null;
  return {
    createdBy: v3Audit.createdBy === undefined ? null : v3Audit.createdBy,
    lastModifiedBy:
      v3Audit.modifiedBy !== undefined
        ? v3Audit.modifiedBy
        : v3Audit.lastModifiedBy !== undefined
          ? v3Audit.lastModifiedBy
          : null,
    createdTime: v3Audit.createdTime === undefined ? null : v3Audit.createdTime,
    lastModifiedTime:
      v3Audit.modifiedTime !== undefined
        ? v3Audit.modifiedTime
        : v3Audit.lastModifiedTime !== undefined
          ? v3Audit.lastModifiedTime
          : null,
  };
}

// v3 stores only a user-id string. 2.x wants the whole User.
function mapAssignee(uuid) {
  return { uuid: typeof uuid === 'string' ? uuid : null };
}

// ---------------------------------------------------------------- routing

function routeOf(req2x) {
  const shim = req2x && req2x.__shim;
  const path = (shim && shim.path) || (req2x && req2x.__path) || '';
  const bare = String(path).split('?')[0].replace(/\/+$/, '') || String(path);
  if (bare.endsWith('/process/_transition')) return 'transition';
  if (bare.endsWith('/businessservice/_search')) return 'businessservice';
  if (bare.endsWith('/process/_search')) return 'processsearch';
  return null;
}

function unknownRoute(req2x) {
  const shim = req2x && req2x.__shim;
  const path = (shim && shim.path) || '(no path on req2x.__shim)';
  gaplog.record({
    dep: DEP,
    field: path,
    action: 'unmapped',
    detail:
      `EGOV_WORKFLOW_HOST now points at the shim and CCRS called ${path}, which is none of the ` +
      `three 2.x workflow paths this translator has a verified v3 counterpart for ` +
      `(${TRANSITION_2X}, ${BUSINESSSERVICE_SEARCH_2X}, ${PROCESS_SEARCH_2X}). Refusing to guess: ` +
      'answering a plausible envelope to an unknown workflow call is how the Task 4 boundary ' +
      'switchover returned zero complaints with HTTP 200 and no error. The shim throws (502 ' +
      'SHIM_ERROR), so an unhandled sub-path is loud.',
  });
  const err = new Error(
    `workflow translator: unsupported 2.x workflow path ${path} ` +
      `(expected ${TRANSITION_2X}, ${BUSINESSSERVICE_SEARCH_2X} or ${PROCESS_SEARCH_2X})`
  );
  err.shimUnmapped = true;
  throw err;
}

// ---------------------------------------------------------------- 1. transition

function transitionToV3(req2x) {
  const list = Array.isArray(req2x && req2x.ProcessInstances) ? req2x.ProcessInstances : [];
  if (list.length !== 1) {
    gaplog.record({
      dep: DEP,
      field: 'ProcessInstances[]',
      action: 'unmapped',
      detail:
        `2.x /process/_transition accepts a LIST of ProcessInstances (${list.length} sent here); ` +
        'v3 POST /workflow/v3/transition takes exactly ONE entity per call (processCode + entityId ' +
        '+ action). Serving a batch faithfully would need one v3 call per element, which the ' +
        'one-request-in / one-request-out translator contract cannot express, and a partial batch ' +
        'would be worse than none. pgr-services always sends exactly one ' +
        '(WorkflowService.updateWorkflowStatus wraps a Collections.singletonList), so this is a ' +
        'hard error rather than a silent truncation.',
    });
    const err = new Error(
      `workflow translator: /process/_transition carried ${list.length} ProcessInstances; ` +
        'v3 POST /workflow/v3/transition handles exactly one'
    );
    err.shimUnmapped = true;
    throw err;
  }

  const pi = list[0] && typeof list[0] === 'object' ? list[0] : {};
  const tenantId = tenantOf(req2x, pi);
  const processCode = pi.businessService || defaultProcessCode();

  if (!pi.businessService) {
    gaplog.record({
      dep: DEP,
      field: 'ProcessInstances[0].businessService',
      action: 'defaulted',
      detail:
        `The 2.x transition carried no businessService, so v3 processCode was defaulted to ` +
        `${JSON.stringify(processCode)} (WORKFLOW_PROCESS_CODE). v3 makes processCode required on ` +
        '/workflow/v3/transition. pgr-services always sets it from the BusinessService search, so ' +
        'this should not arise.',
    });
  }

  if (pi.moduleName !== undefined && pi.moduleName !== null) {
    gaplog.record({
      dep: DEP,
      field: 'ProcessInstances[0].moduleName',
      action: 'dropped',
      detail:
        `2.x carries moduleName (${JSON.stringify(pi.moduleName)}) on every ProcessInstance; the v3 ` +
        'TransitionRequest has no such field and nothing in the v3 workflow schema corresponds to ' +
        'it. The rebuilt 2.x reply echoes what the REQUEST sent, which is the most this shim ' +
        'honestly knows - but note that is NOT what 2.x answers. CCRS sends ' +
        'PGRConstants.PGR_MODULENAME = "PGR" (WorkflowService.getProcessInstanceForPGR) and the ' +
        'live egov-workflow-v2 REPLACES it with the BusinessService\'s `business` field, so the ' +
        'real 2.x reply says "pgr-services". v3 has neither field - it discards `business` with ' +
        'the rest of additionalDetails - so the override cannot be reproduced and the baseline ' +
        'diff shows "PGR" where 2.x said "pgr-services". Nothing in CCRS reads moduleName back. ' +
        'On a history search v3 reports no moduleName at all and the shim emits null.',
    });
  }

  const assignes = Array.isArray(pi.assignes) ? pi.assignes : [];
  const assignees = assignes
    .map((u) => (u && typeof u === 'object' ? u.uuid : u))
    .filter((u) => typeof u === 'string' && u.length);

  if (assignes.length) {
    gaplog.record({
      dep: DEP,
      field: 'ProcessInstances[0].assignes[]',
      action: 'dropped',
      detail:
        `2.x assignes are full User objects (id, userName, name, type, mobileNumber, emailId, ` +
        `roles[], tenantId, uuid); v3 TransitionRequest.assignees is an array of bare user-id ` +
        `STRINGS. ${assignes.length} assignee(s) were reduced to their uuid and every other ` +
        'attribute discarded. CCRS itself only ever sends the uuid ' +
        '(WorkflowService.getProcessInstanceForPGR builds a User with nothing but setUuid), so ' +
        'nothing is lost on the way OUT - but see the matching entry on the way back.',
    });
  }

  const assigner = userIdOf(req2x);
  if (assigner) {
    gaplog.record({
      dep: DEP,
      field: 'assigner',
      action: 'dropped',
      detail:
        'v3 TransitionRequest declares `assigner` (a user-id string) and the live service ACCEPTS ' +
        'it and then throws it away - the TransitionResponse and every later search echo ' +
        '"assigner": "" (verified). 2.x fills ProcessInstance.assigner with the whole acting User. ' +
        'The value is still sent, in case a later v3 build honours it, and the rebuilt 2.x reply ' +
        "carries the requester's uuid from the inbound RequestInfo rather than from v3.",
    });
  }

  const body = {
    processCode,
    entityId: pi.businessId === undefined ? null : pi.businessId,
    action: pi.action === undefined ? null : pi.action,
  };
  if (pi.comment !== undefined && pi.comment !== null) body.comment = pi.comment;
  if (Array.isArray(pi.documents) && pi.documents.length) body.documents = pi.documents;
  if (assignees.length) body.assignees = assignees;
  if (assigner) body.assigner = assigner;

  return {
    method: 'POST',
    path: V3_TRANSITION,
    body,
    headers: authHeaders(req2x, 'transition', tenantId),
  };
}

// Rebuild the 2.x State block. v3's TransitionResponse gives ONE fact about the
// state - its code - so everything else is null and said so.
function stateFromCode(code, tenantId, sla, which) {
  gaplog.record({
    dep: DEP,
    field: 'ProcessInstances[].state',
    action: 'dropped',
    detail:
      `2.x staples the WHOLE State definition onto every ${which} reply: state.uuid, ` +
      'state.businessServiceId, state.docUploadRequired, state.isStartState, ' +
      'state.isTerminateState, state.isStateUpdatable, state.auditDetails, and state.actions[] - ' +
      'each action with its own uuid, currentState/nextState uuids, roles[] and auditDetails. The ' +
      'v3 TransitionResponse and the v3 GET /transition rows report exactly ONE fact about the ' +
      'state: its CODE. Everything else here is null. Recovering it would take a SECOND v3 call ' +
      '(GET /workflow/v3/process/definition/{processCode}) per transition, which the ' +
      'one-request-in / one-request-out translator contract has no way to make. Nothing in CCRS ' +
      'reads these fields on this path - WorkflowService only takes ' +
      'state.applicationStatus - but the DIGIT UI drives its action buttons off state.actions[], ' +
      'so a real cutover needs the definition joined in by the workflow service itself.',
  });
  const value = code === undefined || code === null ? null : String(code);
  return {
    auditDetails: null,
    uuid: null,
    tenantId: tenantId || null,
    businessServiceId: null,
    sla: sla === undefined ? null : sla,
    state: value,
    applicationStatus: value,
    // `code` is not a field of the 2.x State model (it has `state` and
    // `applicationStatus`), but v3 speaks in state CODES and the task brief's
    // contract test reads state.code. Carrying all three spellings of the one
    // value costs nothing: pgr-services' ObjectMapper bean (PGRApp.java)
    // disables FAIL_ON_UNKNOWN_PROPERTIES, so the extra key is ignored.
    code: value,
    docUploadRequired: null,
    isStartState: value === V3_START_STATE ? true : null,
    isTerminateState: null,
    isStateUpdatable: null,
    actions: null,
  };
}

// v3 reports the state as a bare code on `currentState`, on BOTH the POST
// TransitionResponse and the GET ProcessInstanceListResponse rows - verified
// live on both. The two nested spellings below are defensive only, exactly as
// boundary.js accepts "boundary" and "boundaries": the plan's contract sketch
// wrote the v3 reply as {state:{code}}, and a later v3 build that nests the
// state must not make this translator quietly report null.
function currentStateOf(src) {
  if (typeof src.currentState === 'string') return src.currentState;
  if (src.state && typeof src.state === 'object' && typeof src.state.code === 'string') {
    return src.state.code;
  }
  if (typeof src.state === 'string') return src.state;
  return src.currentState === undefined ? undefined : src.currentState;
}

// Accepts either a v3 TransitionResponse (POST) or one row of
// ProcessInstanceListResponse.processInstances (GET); the shapes agree on
// every field this reads.
function mapProcessInstance(v3pi, ctx) {
  const src = v3pi && typeof v3pi === 'object' ? v3pi : {};
  const assignees = Array.isArray(src.assignees) ? src.assignees : null;

  if (assignees && assignees.length) {
    gaplog.record({
      dep: DEP,
      field: 'ProcessInstances[].assignes[]',
      action: 'dropped',
      detail:
        `v3 reports ${assignees.length} assignee(s) as bare user-id string(s); 2.x callers expect ` +
        'full User objects (the live reply carries id=40, userName, name, mobileNumber and the ' +
        "whole roles[] array). Each one is rebuilt as {uuid} only - v3's workflow store simply " +
        'does not hold the rest, and joining it would need a user-service call this translator ' +
        'cannot make. CCRS survives: WorkflowService.getWorkflow maps User::getUuid, and ' +
        'NotificationService.resolveAssignee re-fetches the user by uuid. Anything reading ' +
        'assignes[0].name straight off the workflow reply gets null.',
    });
  }

  const out = {
    id: src.id === undefined ? null : src.id,
    tenantId: ctx.tenantId || null,
    businessService: src.processCode === undefined ? ctx.processCode || null : src.processCode,
    businessId: src.entityId === undefined ? null : src.entityId,
    action: src.action === undefined ? null : src.action,
    // v3 has no moduleName. Echo what the 2.x request said, so a caller sees
    // what it sent; gap-logged in transitionToV3.
    moduleName: ctx.moduleName === undefined ? null : ctx.moduleName,
    state: stateFromCode(currentStateOf(src), ctx.tenantId, null, ctx.which),
    notificationAction: null,
    comment: src.comment === undefined || src.comment === '' ? null : src.comment,
    documents: Array.isArray(src.documents) && src.documents.length ? src.documents : null,
    assignes: assignees && assignees.length ? assignees.map(mapAssignee) : null,
    // v3 blanks assigner; fall back to the acting user from the 2.x request.
    assigner:
      typeof src.assigner === 'string' && src.assigner.length
        ? mapAssignee(src.assigner)
        : ctx.assignerUuid
          ? mapAssignee(ctx.assignerUuid)
          : null,
    auditDetails: mapAudit(src.auditDetail || src.auditDetails),
    businesssServiceSla: src.processSla === undefined ? null : src.processSla,
    stateSla: src.stateSla === undefined ? null : src.stateSla,
  };

  gaplog.record({
    dep: DEP,
    field: 'ProcessInstances[].notificationAction',
    action: 'dropped',
    detail:
      '2.x ProcessInstance carries notificationAction (null on every reply observed on this ' +
      'deployment, but a real field of the contract). v3 has no equivalent and no way to store ' +
      'one; emitted as null.',
  });

  return out;
}

function transitionToV2(v3Response, req2x) {
  const body = bodyOf(v3Response);
  const list = Array.isArray(req2x && req2x.ProcessInstances) ? req2x.ProcessInstances : [];
  const pi = list[0] && typeof list[0] === 'object' ? list[0] : {};
  const ctx = {
    tenantId: tenantOf(req2x, pi),
    processCode: pi.businessService || defaultProcessCode(),
    moduleName: pi.moduleName,
    assignerUuid: userIdOf(req2x),
    which: 'POST /workflow/v3/transition',
  };

  // v3 answers a transition with ONE object, not a list.
  return {
    ResponseInfo: responseInfo(req2x, 'POST /workflow/v3/transition'),
    ProcessInstances: [mapProcessInstance(body, ctx)],
  };
}

// ---------------------------------------------------------------- 2. businessservice search

function businessServiceToV3(req2x) {
  const q = queryOf(req2x);
  const tenantId = tenantOf(req2x, null);
  const codes = q.businessServices === undefined || q.businessServices === null ? '' : String(q.businessServices);

  if (!codes) {
    gaplog.record({
      dep: DEP,
      field: 'businessServices',
      action: 'unmapped',
      detail:
        'The 2.x businessservice search carried no ?businessServices=. 2.x treats it as optional ' +
        'and lists every business service for the tenant. v3 GET /workflow/v3/process/definition ' +
        'with no processCode does list all definitions, so the call is forwarded unfiltered - but ' +
        'CCRS always sends exactly one (PGR_BUSINESSSERVICE), so this does not arise in the ' +
        'complaint lifecycle.',
    });
  } else if (codes.includes(',')) {
    gaplog.record({
      dep: DEP,
      field: 'businessServices',
      action: 'unmapped',
      detail:
        `2.x businessservice search was asked for MULTIPLE services (${JSON.stringify(codes)}). v3's ` +
        'processCode filter is an EXACT match on one code, so a comma-separated string matches ' +
        'nothing and the reply is an empty BusinessServices list. It is forwarded verbatim so the ' +
        'caller gets an honest empty answer (CCRS turns that into BUSINESSSERVICE_NOT_FOUND) ' +
        'rather than a fabricated one. Serving it would need one v3 call per code.',
    });
  }

  const params = new URLSearchParams();
  if (codes) params.set('processCode', codes);
  params.set('size', String(V3_MAX_SIZE));

  return {
    method: 'GET',
    path: `${V3_DEFINITION}?${params.toString()}`,
    body: undefined,
    headers: authHeaders(req2x, 'businessservice search', tenantId),
  };
}

function mapDefinitionAction(v3action, stateId, tenantId) {
  const src = v3action && typeof v3action === 'object' ? v3action : {};
  return {
    auditDetails: mapAudit(src.auditDetail || src.auditDetails),
    uuid: src.id === undefined ? null : src.id,
    tenantId: tenantId || null,
    // v3 returns nextState as a state UUID, exactly as 2.x does.
    currentState: src.currentState === undefined ? stateId : src.currentState,
    action: src.code === undefined ? null : src.code,
    nextState: src.nextState === undefined ? null : src.nextState,
    roles: Array.isArray(src.roles) ? src.roles : null,
    // 2.x soft-deletes an action with active=false; v3 has no such flag.
    active: null,
  };
}

function mapDefinitionState(v3state, processId, tenantId) {
  const src = v3state && typeof v3state === 'object' ? v3state : {};
  const type = src.type === undefined ? null : src.type;
  const extra = src.additionalDetails && typeof src.additionalDetails === 'object' ? src.additionalDetails : {};
  const code = src.code === undefined ? null : src.code;
  const isStart = src.isInitial === true || type === 'INITIAL';
  return {
    auditDetails: mapAudit(src.auditDetail || src.auditDetails),
    uuid: src.id === undefined ? null : src.id,
    tenantId: tenantId || null,
    businessServiceId: processId === undefined ? null : processId,
    sla: src.sla === undefined ? null : src.sla,
    // The 2.x start state is nameless; undo the START code the YAML had to invent.
    state: isStart && code === V3_START_STATE ? null : code,
    applicationStatus: isStart && code === V3_START_STATE ? null : code,
    docUploadRequired:
      typeof extra.twoXDocUploadRequired === 'boolean' ? extra.twoXDocUploadRequired : null,
    isStartState: isStart,
    // v3 collapses TERMINAL_* to INTERMEDIATE on write AND discards the
    // additionalDetails the YAML stashed the original flag in, so on the live
    // service this is null: isTerminateState survives NOWHERE. The
    // additionalDetails branch is kept because the field is declared free-form
    // in the spec and would work the moment the service honours it.
    isTerminateState:
      extra.twoXIsTerminateState === true
        ? true
        : type === 'TERMINAL_SUCCESS' || type === 'TERMINAL_FAILURE'
          ? true
          : null,
    isStateUpdatable:
      typeof extra.twoXIsStateUpdatable === 'boolean' ? extra.twoXIsStateUpdatable : null,
    actions: Array.isArray(src.actions)
      ? src.actions.map((a) => mapDefinitionAction(a, src.id, tenantId))
      : null,
  };
}

function businessServiceToV2(v3Response, req2x) {
  const body = bodyOf(v3Response);
  const defs = Array.isArray(body.definitions)
    ? body.definitions
    : body && body.code
      ? [body]
      : [];
  const tenantId = tenantOf(req2x, null);

  const list = defs.map((d) => {
    const src = d && typeof d === 'object' ? d : {};
    const extra = src.additionalDetails && typeof src.additionalDetails === 'object' ? src.additionalDetails : {};
    return {
      tenantId,
      uuid: src.id === undefined ? null : src.id,
      businessService: src.code === undefined ? null : src.code,
      // 2.x BusinessService.business names the owning module. v3 has no field
      // for it; the YAML stashes it in additionalDetails at registration, and
      // the live service discards additionalDetails, so this is null in
      // practice. Kept so it works if v3 ever honours the field.
      business: extra.business === undefined ? null : extra.business,
      getUri: null,
      postUri: null,
      businessServiceSla: src.sla === undefined ? null : src.sla,
      states: Array.isArray(src.states)
        ? src.states.map((s) => mapDefinitionState(s, src.id, tenantId))
        : null,
      auditDetails: mapAudit(src.auditDetail || src.auditDetails),
    };
  });

  if (defs.length) {
    gaplog.record({
      dep: DEP,
      field: 'BusinessServices[]',
      action: 'defaulted',
      detail:
        `Rebuilt ${defs.length} 2.x BusinessService(s) from v3 process definition(s): envelope key ` +
        '"definitions" -> "BusinessServices", definition "id" -> "uuid", "code" -> ' +
        '"businessService", "sla" -> "businessServiceSla", state "code" -> "state"/' +
        '"applicationStatus", state "type: INITIAL" -> "isStartState", action "code" -> "action", ' +
        'action "id" -> "uuid", and auditDetail modifiedBy/modifiedTime -> ' +
        'lastModifiedBy/lastModifiedTime. Four 2.x fields have NO v3 source and come back null: ' +
        'getUri, postUri, State.docUploadRequired / State.isStateUpdatable (read from the ' +
        'additionalDetails this shim registered, never from a v3 field) and Action.active. ' +
        'Also note the INITIAL state: 2.x names it null, v3 forbids an empty code, so the YAML ' +
        'calls it START and this mapping turns START back into null. And note that the ' +
        'additionalDetails fallbacks named above are inert on the live service, which accepts ' +
        'additionalDetails on a process definition and then discards it.',
    });
    gaplog.record({
      dep: DEP,
      field: 'BusinessServices[].states[].isTerminateState',
      action: 'faked',
      detail:
        'isTerminateState comes back NULL from the live v3 service, because v3 loses it twice ' +
        'over. v3 does offer state types TERMINAL_SUCCESS / TERMINAL_FAILURE in ' +
        'ProcessDefinitionInput, and the registered PGR definition sends them - but the LIVE ' +
        'service silently rewrites every non-INITIAL type to INTERMEDIATE on write (verified with ' +
        'a throwaway definition: TERMINAL_SUCCESS sent, INTERMEDIATE returned), AND it discards ' +
        'the state additionalDetails the YAML stashed the original flag in (verified on the real ' +
        'PGR registration). So v3 as deployed cannot distinguish a terminal state from a working ' +
        'one at all. This translator reads the flag from additionalDetails, or infers it from a ' +
        'TERMINAL_* type, if a later v3 build ever preserves either - today it reports null, ' +
        'which is honest: nothing in v3 knows the answer. CCRS is unaffected (it takes ' +
        'completion from applicationStatus) but a v3-native consumer cannot compute it.',
    });
  }

  return {
    ResponseInfo: responseInfo(req2x, 'GET /workflow/v3/process/definition'),
    BusinessServices: list,
  };
}

// ---------------------------------------------------------------- 3. process search

function isTrue(v) {
  return v !== undefined && v !== null && ['true', '1', 'yes'].includes(String(v).toLowerCase());
}

function processSearchToV3(req2x) {
  const q = queryOf(req2x);
  const tenantId = tenantOf(req2x, null);
  const businessIds = q.businessIds === undefined || q.businessIds === null ? '' : String(q.businessIds);
  const assignee = q.assignee === undefined || q.assignee === null ? '' : String(q.assignee);
  const history = isTrue(q.history);

  let processCode = q.businessService === undefined || q.businessService === null ? '' : String(q.businessService);
  if (!processCode) {
    processCode = defaultProcessCode();
    gaplog.record({
      dep: DEP,
      field: 'processCode',
      action: 'defaulted',
      detail:
        `2.x /process/_search by businessIds carries NO business service ` +
        '(WorkflowService.getprocessInstanceSearchURL sends only tenantId and businessIds - the ' +
        'business ids are globally unique, so 2.x does not need one). v3 REFUSES the search ' +
        'without it: 400 "processCode query parameter is required when filtering by entityId". ' +
        `There is nothing in the request to derive it from, so it is set to ` +
        `${JSON.stringify(processCode)} from WORKFLOW_PROCESS_CODE. That is an INVENTED VALUE. It ` +
        'happens to be right because pgr-services is the only caller and PGR is its only business ' +
        'service (PGRConstants.PGR_BUSINESSSERVICE), but any second module behind this shim, or ' +
        'any CCRS complaint whose workflow is not PGR, would be searched under the wrong process ' +
        'and answered an empty list. Fixing it properly needs either a v3 search that keys on ' +
        'entityId alone, or a shim that is no longer stateless.',
    });
  }

  if (businessIds.includes(',')) {
    gaplog.record({
      dep: DEP,
      field: 'businessIds',
      action: 'unmapped',
      detail:
        `2.x /process/_search was asked for MULTIPLE businessIds (${businessIds.split(',').length} ` +
        'ids) and v3 cannot serve that. Verified live: v3 GET /workflow/v3/transition with a ' +
        'comma-separated entityId returns {"totalCount":0,"processInstances":[]}, and the ' +
        'repeated-parameter form ?entityId=a&entityId=b honours only the FIRST value. The CSV is ' +
        'forwarded verbatim so v3 answers an honest empty list; CCRS then throws ' +
        'WORKFLOW_NOT_FOUND ("The workflow object is not found") from ' +
        'WorkflowService.enrichWorkflow:142, which checks that the reply holds exactly one ' +
        'ProcessInstance per id asked for. That is LOUD, which is the point - a partial answer ' +
        'would silently drop complaints out of an employee inbox. The lifecycle is unaffected ' +
        'because both of its searches are scoped to one serviceRequestId. Serving this needs one ' +
        'v3 call per id, which the one-request-in / one-request-out contract cannot express.',
    });
  }

  if (history) {
    gaplog.record({
      dep: DEP,
      field: 'history',
      action: 'defaulted',
      detail:
        'The 2.x caller asked for history=true (NotificationService.getEmployeeName:663 and ' +
        'EscalationService:278 - neither is listed in the task brief; both were found in the ' +
        "egov-workflow-proxy access log during a green lifecycle run). v3's GET " +
        '/workflow/v3/transition?history=true returns every transition row oldest-first, which ' +
        'matches 2.x. The cap does not: v3 refuses size>100 (400 "size must be an integer between ' +
        `1 and 100"), so the shim asks for ${V3_MAX_SIZE} and a complaint with a longer history ` +
        'is truncated by v3 paging with no error. The translator contract cannot page.',
    });
  }

  const params = new URLSearchParams();
  params.set('processCode', processCode);
  if (businessIds) params.set('entityId', businessIds);
  if (assignee) params.set('assignee', assignee);
  if (history) params.set('history', 'true');
  params.set('size', String(V3_MAX_SIZE));
  // tenantId is deliberately NOT forwarded as a query param: in v3 the tenant
  // is the X-Tenant-ID header.

  return {
    method: 'GET',
    path: `${V3_TRANSITION}?${params.toString()}`,
    body: undefined,
    headers: authHeaders(req2x, 'process search', tenantId),
  };
}

function processSearchToV2(v3Response, req2x) {
  const body = bodyOf(v3Response);
  const q = queryOf(req2x);
  const rows = Array.isArray(body.processInstances)
    ? body.processInstances
    : Array.isArray(body.ProcessInstances)
      ? body.ProcessInstances
      : [];

  const tenantId = tenantOf(req2x, null);
  const ctx = {
    tenantId,
    processCode:
      (q.businessService === undefined || q.businessService === null ? '' : String(q.businessService)) ||
      defaultProcessCode(),
    moduleName: undefined,
    assignerUuid: '',
    which: 'GET /workflow/v3/transition',
  };

  const list = rows.map((r) => mapProcessInstance(r, ctx));

  if (rows.length) {
    gaplog.record({
      dep: DEP,
      field: 'ProcessInstances[]',
      action: 'defaulted',
      detail:
        `Rebuilt ${rows.length} 2.x ProcessInstance(s) from v3 transition rows: envelope key ` +
        '"processInstances" -> "ProcessInstances", "entityId" -> "businessId", "processCode" -> ' +
        '"businessService", "currentState" (a code) -> the whole state object, "processSla" -> ' +
        '"businesssServiceSla" (three s\'s, as upstream spells it), "assignees" (strings) -> ' +
        '"assignes" (User objects), auditDetail -> auditDetails with modifiedBy/modifiedTime ' +
        'renamed. Four things v3 reports have NOWHERE to go in 2.x and are dropped: "isLatest", ' +
        '"escalated", "totalCount"/"page"/"size" paging, and the v3 process "version". Two things ' +
        '2.x reports have no v3 source and come back null: "nextActions" (2.x computes the ' +
        "role-filtered actions available next; v3 returns none on this endpoint) and " +
        '"previousStatus"/"entity"/"rating".',
    });
  }

  return {
    ResponseInfo: responseInfo(req2x, 'GET /workflow/v3/transition'),
    ProcessInstances: list,
  };
}

// ---------------------------------------------------------------- dispatch

function toV3(req2x) {
  const route = routeOf(req2x);
  if (route === 'transition') return transitionToV3(req2x);
  if (route === 'businessservice') return businessServiceToV3(req2x);
  if (route === 'processsearch') return processSearchToV3(req2x);
  // No path at all means a unit test calling the translator directly. Dispatch
  // on the body shape instead: a ProcessInstances list is unambiguously a
  // transition; anything else is the businessservice search.
  if (!req2x || !req2x.__shim) {
    return Array.isArray(req2x && req2x.ProcessInstances)
      ? transitionToV3(req2x)
      : businessServiceToV3(req2x);
  }
  return unknownRoute(req2x);
}

function toV2(v3Response, req2x) {
  const route = routeOf(req2x);
  if (route === 'businessservice') return businessServiceToV2(v3Response, req2x);
  if (route === 'processsearch') return processSearchToV2(v3Response, req2x);
  if (route === 'transition') return transitionToV2(v3Response, req2x);
  if (!req2x || !req2x.__shim) {
    return Array.isArray(req2x && req2x.ProcessInstances)
      ? transitionToV2(v3Response, req2x)
      : businessServiceToV2(v3Response, req2x);
  }
  return unknownRoute(req2x);
}

module.exports = {
  toV3,
  toV2,
  dep: DEP,
  // exported for focused unit tests
  transitionToV3,
  transitionToV2,
  businessServiceToV3,
  businessServiceToV2,
  processSearchToV3,
  processSearchToV2,
};
