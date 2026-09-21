// src/translators/user.js
//
// CCRS (DIGIT 2.12) egov-user  ->  DIGIT 3.0 individual.
//
// =====================================================================
// THE HEADLINE: THIS ONE DOES NOT WORK, AND THE REASON IS IDENTITY
// =====================================================================
// CCRS mints its own session. `POST {kong}/user/oauth/token` is served by the
// 2.x egov-user service and answers with a UUID access token plus a
// `UserRequest` whose `uuid` is a row id in the egov-user Postgres schema.
// Every later CCRS call carries that uuid as `RequestInfo.userInfo.uuid`, and
// `EnrichmentService.enrichCreateRequest:64` copies it onto the complaint:
//
//     if (userInfo.getType().equalsIgnoreCase(CITIZEN))
//         service.setAccountId(requestInfo.getUserInfo().getUuid());
//
// `UserService.enrichUser:110` then asks the user service for exactly that id
// and throws if it is not there:
//
//     if (userDetailResponse.getUser().isEmpty())
//         throw new CustomException("INVALID_ACCOUNTID",
//                                   "No user exist for the given accountId");
//
// DIGIT 3.0's individual registry has never heard of that uuid, and - this is
// the part a shim cannot work around - THERE IS NO WAY TO TEACH IT.
// Verified live on this box:
//
//   * POST /individual/v3/individuals with an `id` in the body SILENTLY
//     IGNORES it and mints a server-side uuid. Sent
//     aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee, got 629b9122-bc18-48d7-80fc-...
//     (201). So an individual cannot be created AT a chosen 2.x uuid.
//   * The `userId` query filter - the one field in the v3 Individual schema
//     that could hold a foreign identity - is ACCEPTED AND IGNORED by the
//     deployed service. `?userId=<bogus>` returned every individual in the
//     tenant with HTTP 200. So even storing the 2.x uuid in `userId` would not
//     let anyone search for it.
//   * `additionalAttributes` can hold the 2.x uuid, but it is not a search
//     filter, so it is write-only for this purpose.
//
// A stateless translator therefore has no mapping from a 2.x user uuid to a v3
// individual id, and a stateful one would be a user-migration service, not a
// shim. That is the identity-migration requirement, and it is the deliverable
// of this task: before CCRS can be pointed at v3's individual registry, every
// 2.x egov-user row must be migrated into it AND the sessions must be issued
// by the same identity provider both sides trust - Keycloak, which this v3
// stack runs (d3-keycloak, KEYCLOAK_ENABLED=true on d3-individual) and which
// CCRS's own login does not use.
//
// The translator below is written anyway, and written honestly: it translates
// what v3 can really answer, it throws on what it cannot, and it gap-logs
// every single invented field. When the individual exists it works; when it
// does not, CCRS gets an empty user list and raises INVALID_ACCOUNTID - loudly,
// which is the correct behaviour.
//
// =====================================================================
// ENDPOINT INVENTORY - COUNTED, NOT ASSUMED
// =====================================================================
// Task 4 found EGOV_BOUNDARY_HOST served 2 paths; Task 5 found
// EGOV_WORKFLOW_HOST served 5 shapes, one of which existed in no Java call
// site. So EGOV_USER_HOST was counted the same two ways.
//
// (a) Java. pgr-services builds its user URIs from four properties
//     (application.properties:73-78):
//        egov.user.host           = EGOV_USER_HOST
//        egov.user.context.path   = /user/users
//        egov.user.search.path    = /user/_search
//        egov.user.create.path    = /_createnovalidate
//        egov.user.update.path    = /_updatenovalidate
//     giving THREE distinct paths, reached from six call sites in
//     service/UserService.java:
//        /user/_search                    searchUser:180 (by uuid, by userName),
//                                         searchBulkUser:203 (by uuid list),
//                                         getFirstRoleNameByUuid:229,
//                                         enrichUserIds:283 (by mobileNumber)
//        /user/users/_createnovalidate    createUser:130
//        /user/users/_updatenovalidate    updateUser:155, updateUserContactDetails:261
//
// (b) The access log. pgr-services reaches egov-user through the nginx
//     pass-through `egov-user-proxy:8107` (/opt/digit/nginx/user-proxy.conf -
//     `location / { proxy_pass http://egov-user:8107; }`, no added semantics).
//     Grouping three days of that log by source container IP:
//
//        digit-pgr-services-1 (172.19.0.38)  POST /user/_search                  477
//                                            POST /user/users/_createnovalidate    9
//                                            POST /user/users/_updatenovalidate    1
//        kong-gateway         (172.19.0.35)  POST /user/_details?access_token=  2696
//                                            POST /user/oauth/token              115
//        egov-hrms            (172.19.0.36)  POST /user/v1/_search               129
//        digit-egov-workflow-v2-1 (.30)      POST /user/_search                  179
//
//     So the log holds FIVE distinct paths, but only THREE of them are
//     pgr-services'. The other two matter enormously and are the reason this
//     switchover breaks the way it does rather than the way it looks like it
//     should:
//
//       * `POST /user/_details?access_token=` is the GATEWAY validating the
//         session on every single inbound CCRS request. It is issued by
//         kong-gateway, which resolves egov-user through its own upstream
//         configuration, NOT through pgr-services' EGOV_USER_HOST. Flipping
//         this variable therefore does NOT move authentication: CCRS logins
//         keep working and keep minting 2.x uuids. The shim never sees a
//         token-validation call, and could not answer one if it did - v3 has
//         no concept of a 2.x opaque access token.
//       * `POST /user/v1/_search` is egov-hrms'. Also unaffected.
//
//     A FOURTH pgr path would have shown up here. None did.
//
// Anything other than the three pgr paths throws (502 SHIM_ERROR) plus an
// `unmapped` gap entry. It is never best-effort translated - Task 4's lesson
// is that a plausible empty 200 is worse than an error.
//
// =====================================================================
// WHAT v3's INDIVIDUAL CANNOT EXPRESS (each one is in gaps.jsonl)
// =====================================================================
//  * NO ROLES. CCRS's User carries roles[]. The v3 Individual schema has no
//    roles field and the service REJECTS one ("unknown field \"roles\"" - 400).
//    Roles live in Keycloak realm roles (d3-individual is configured with
//    KEYCLOAK_ASSIGNABLE_ROLES=ASSIGNER,CITIZEN), which is a different system
//    with a different API and no tenant-scoped role codes of CCRS's shape.
//    So the SEARCH path answers roles: [] and type: null - it reports what v3
//    reports, which is nothing, rather than stamping a plausible "CITIZEN" on
//    every individual including employees.
//    WHAT THAT COSTS, traced rather than assumed: pgr-services' authorisation
//    does NOT read this. Every role/type check - PGRService.hasAnyRole:438
//    (confidential-field masking), Principals.isPureCitizen:55,
//    RoleCodes.normalize:29, EmployeeContextService:154, PolicyInputBuilder:27,
//    PolicyDrivenScopeResolver:86 - takes
//    org.egov.common.contract.request.User, i.e. RequestInfo.userInfo, filled
//    by the GATEWAY from the 2.x token, which EGOV_USER_HOST does not move.
//    The shim answers into org.egov.pgr.web.models.User, a DIFFERENT class.
//    Exactly one consumer reads roles off it: getFirstRoleNameByUuid:241 ->
//    ComplaintDomainEventService.getAssigneeName:226, a display string on an
//    outbound event that falls back to "NA". Nothing reads its `type` at all.
//  * NO USER TYPE. CITIZEN / EMPLOYEE / SYSTEM does not exist in v3. Every 2.x
//    user search carries `userType` as a FILTER; v3 cannot apply it, so the
//    search is silently widened. An employee's individual record would come
//    back from a CITIZEN-only search.
//  * NO userName. Rejected as an unknown field on create (400).
//  * NO numeric id. CCRS's User.id is a Long (the egov-user primary key). v3
//    has only uuids.
//  * NO tenantId in the body. It travels only as the X-Tenant-ID header and is
//    not echoed in the response.
//  * gender is MANDATORY in v3 and ABSENT from CCRS's User model entirely.
//    (The spec says "gender is optional"; the deployed service answers 400
//    "gender is required". Spec and implementation disagree.)
//  * givenName must match ^[A-Za-z ]+$ by default: a citizen whose 2.x name
//    holds a digit, a dot or an initial is REJECTED (400), verified with
//    "Zz9 Probe.".
//  * UPDATE IS IMPOSSIBLE under this contract. PUT /individuals/{id} is an
//    optimistic-concurrency API: `version` is required, and sending none is
//    409 ROW_VERSION_MISMATCH {"expected":1,"provided":0} (verified). The 2.x
//    payload carries no version, so the shim would have to GET the individual
//    first - a SECOND v3 call, which one-request-in / one-request-out cannot
//    express. _updatenovalidate therefore throws.
//
// =====================================================================
// WHAT v3 DOES BETTER THAN THE WORKFLOW SERVICE
// =====================================================================
// The repeated-parameter multi-id form WORKS here: `?id=a&id=b` really does
// OR the two and returned both (and `?id=<known>&id=<unknown>` returned
// totalCount 1). That is the exact thing the v3 workflow service could not do,
// so searchBulkUser - the employee-inbox path that Task 5 found broken - is
// servable by the individual registry. The comma form is NOT: `?id=a,b`
// answers 400. This translator uses the repeated form.
//
// =====================================================================
// A DEPLOYMENT PREREQUISITE THAT HAD TO BE FIXED FIRST
// =====================================================================
// Before any of the above could be probed, EVERY create returned
// 500 ENCRYPTION_ERROR. d3-individual encrypts PII through Vault transit, and
// on this box the transit secrets engine was NEVER MOUNTED:
//   vault returned status 404: no handler for route
//   "transit/encrypt/IndividualEncrypt". route entry not found.
// `vault secrets enable transit` + `vault write -f transit/keys/IndividualEncrypt`
// (see register-individual-transit.sh) fixed it. This is recorded as a gap: a
// v3 stack can come up "healthy", answer searches with 200, and be incapable
// of storing a single person.
const gaplog = require('../gaplog');
// The one stateful component in the shim: the durable 2.x-uuid -> v3-individual-id
// map. Everything this translator does with it is on the record below.
const idmap = require('../idmap');

const DEP = 'egov-user';

// --- 2.x paths ---------------------------------------------------------
const SEARCH_2X = '/user/_search';
const CREATE_2X = '/user/users/_createnovalidate';
const UPDATE_2X = '/user/users/_updatenovalidate';

// --- v3 ----------------------------------------------------------------
const V3_INDIVIDUALS = '/individual/v3/individuals';
const V3_MAX_SIZE = 100; // verified: ?size=200 -> 400

// v3 requires a gender and CCRS's User model has no such field.
const GENDER_FALLBACK = process.env.INDIVIDUAL_DEFAULT_GENDER || 'OTHER';

// UserUtils.parseResponse insists on this exact format for `createdDate`.
const DATE_FMT_2X = 'dd-MM-yyyy HH:mm:ss';

// ---------------------------------------------------------------- helpers

function queryOf(req2x) {
  if (!req2x || typeof req2x !== 'object') return {};
  if (req2x.__query && typeof req2x.__query === 'object') return req2x.__query;
  if (req2x.__shim && req2x.__shim.query && typeof req2x.__shim.query === 'object') {
    return req2x.__shim.query;
  }
  return {};
}

// The two create/update payloads spell it `requestInfo` (CreateUserRequest),
// the search payload spells it `RequestInfo` (UserSearchRequest). Both.
function requestInfoOf(req2x) {
  if (!req2x || typeof req2x !== 'object') return {};
  if (req2x.RequestInfo && typeof req2x.RequestInfo === 'object') return req2x.RequestInfo;
  if (req2x.requestInfo && typeof req2x.requestInfo === 'object') return req2x.requestInfo;
  return {};
}

function userInfoOf(req2x) {
  const ui = requestInfoOf(req2x).userInfo;
  return ui && typeof ui === 'object' ? ui : {};
}

// Tenant is never hardcoded. UserSearchRequest.tenantId is what CCRS actually
// searches with (always the state-level tenant, via
// UserUtils.getStateLevelTenant), so it wins; the user being created is next,
// then the caller's own tenant.
function tenantOf(req2x) {
  const q = queryOf(req2x);
  const user = req2x && typeof req2x.user === 'object' && req2x.user ? req2x.user : {};
  const ui = userInfoOf(req2x);
  const ri = requestInfoOf(req2x);
  return req2x.tenantId || user.tenantId || q.tenantId || ui.tenantId || ri.tenantId || '';
}

function userIdOf(req2x) {
  return userInfoOf(req2x).uuid || '';
}

// v3 individual needs no token at all: the deployed service authorises on
// X-Tenant-ID / X-User-ID alone (verified - a create with no Authorization
// header answered 201). Unlike the workflow service it enforces no roles, so
// nothing is forged here. That is itself worth knowing: the two v3 services in
// this stack disagree about how a caller proves anything.
function authHeaders(req2x, tenantId) {
  return { 'X-Tenant-ID': tenantId, 'X-User-ID': userIdOf(req2x) };
}

function bodyOf(v3Response) {
  if (!v3Response || typeof v3Response !== 'object') return {};
  if ('json' in v3Response || 'status' in v3Response || 'raw' in v3Response) {
    return v3Response.json && typeof v3Response.json === 'object' ? v3Response.json : {};
  }
  return v3Response;
}

function statusOf(v3Response) {
  return v3Response && typeof v3Response === 'object' && typeof v3Response.status === 'number'
    ? v3Response.status
    : 200;
}

function responseInfo(req2x, which) {
  const ri = requestInfoOf(req2x);
  gaplog.record({
    dep: DEP,
    field: 'responseInfo',
    action: 'faked',
    detail:
      `v3 ${which} returns its payload and nothing else - DIGIT 3.0 has no ResponseInfo envelope. ` +
      'The 2.x envelope is reconstructed from the inbound RequestInfo (apiId/ver/msgId echoed) with ' +
      'ts=null, resMsgId="uief87324" and status="successful". Nothing in it is reported by v3. ' +
      'Note UserDetailResponse spells the key "responseInfo" (lower case r), unlike the workflow ' +
      'contract, and pgr-services never reads it.',
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

function pad(n, w) {
  return String(n).padStart(w, '0');
}

// UserUtils.parseResponse ALWAYS does
//   map.put("createdDate", dateTolong((String) map.get("createdDate"), "dd-MM-yyyy HH:mm:ss"))
// with no null check, so a user object without a parseable createdDate makes
// pgr-services throw a NullPointerException inside SimpleDateFormat.parse().
// The field is therefore mandatory for the response to be readable at all.
function formatDate2x(ms) {
  const d = new Date(typeof ms === 'number' && Number.isFinite(ms) ? ms : Date.now());
  return (
    `${pad(d.getUTCDate(), 2)}-${pad(d.getUTCMonth() + 1, 2)}-${d.getUTCFullYear()} ` +
    `${pad(d.getUTCHours(), 2)}:${pad(d.getUTCMinutes(), 2)}:${pad(d.getUTCSeconds(), 2)}`
  );
}

// ---------------------------------------------------------------- routing

function pathOf(req2x) {
  const shim = req2x && req2x.__shim;
  // `req2x.path` supports calling the translator directly from a unit test,
  // which is how the task brief's own two contract tests are written.
  return (shim && shim.path) || (req2x && (req2x.__path || req2x.path)) || '';
}

function routeOf(req2x) {
  const bare = String(pathOf(req2x)).split('?')[0].replace(/\/+$/, '');
  if (!bare) return null;
  if (bare.endsWith(CREATE_2X)) return 'create';
  if (bare.endsWith(UPDATE_2X)) return 'update';
  if (bare.endsWith(SEARCH_2X)) return 'search';
  return null;
}

function unknownRoute(req2x) {
  const path = pathOf(req2x);
  const hasContext = Boolean(path);
  const field = hasContext ? String(path) : '(no path: req2x carried no __shim)';
  gaplog.record({
    dep: DEP,
    field,
    action: 'unmapped',
    detail: hasContext
      ? `EGOV_USER_HOST now points at the shim and CCRS called ${path}, which is none of the three ` +
        `2.x user paths pgr-services is known to issue (${SEARCH_2X}, ${CREATE_2X}, ${UPDATE_2X} - ` +
        'counted from both service/UserService.java and three days of the egov-user-proxy access ' +
        'log). Refusing to guess. In particular, if this is /user/_details or /user/oauth/token then ' +
        'something has re-pointed AUTHENTICATION at the shim, which cannot be served at all: a 2.x ' +
        'opaque access token has no meaning anywhere in DIGIT 3.0. The shim throws (502 SHIM_ERROR) ' +
        'so an unhandled sub-path is loud rather than plausibly empty.'
      : 'A request reached the user translator with no __shim transport context, so there is no ' +
        'inbound path to dispatch on. server.js sets __shim on EVERY request (makeReq2x, ' +
        'unconditionally) and server.js:145/153 are the ONLY call sites of any translator, so this ' +
        'cannot happen on a live path - it means the translator was called directly, by a test or ' +
        'by future code. Refusing to fall back to body-shape guessing: that fallback would have ' +
        'dispatched an unknown call as a user SEARCH and answered {responseInfo, user: []}, which ' +
        'CCRS reads as the meaningful answer "no such person" and acts on - INVALID_ACCOUNTID on a ' +
        'create, a null citizen on a search. That is exactly the Task 4 failure mode (a plausible ' +
        'empty 200), kept alive in production code for the convenience of tests. Throwing instead.',
  });
  const err = new Error(
    hasContext
      ? `user translator: unsupported 2.x user path ${path} ` +
        `(expected ${SEARCH_2X}, ${CREATE_2X} or ${UPDATE_2X})`
      : 'user translator: req2x carried no __shim transport context, so the inbound 2.x path is ' +
        'unknown and no route can be chosen. server.js always sets it; a direct caller must supply ' +
        `{__shim:{path}} naming one of ${SEARCH_2X}, ${CREATE_2X} or ${UPDATE_2X}.`
  );
  err.shimUnmapped = true;
  throw err;
}

// ---------------------------------------------------------------- 1. search

// 2.x uuid -> v3 individual id, through the durable map. Returns the uuid
// unchanged when the map has no entry, so a miss degrades to exactly the
// behaviour this file had before the map existed.
function resolveUuid(uuid2x, tenantId) {
  let entry = null;
  try {
    entry = idmap.lookup(uuid2x);
  } catch (err) {
    // A map that cannot be read is not "no mapping": it is an unknown answer
    // to "which human is this", so it stops the request rather than widening
    // it. The throw lands in server.js's catch, which gap-logs it and answers
    // 502 - loud, not silent.
    gaplog.record({
      dep: DEP,
      field: 'idmap',
      action: 'unmapped',
      detail:
        `The durable 2.x-uuid -> individual-id map at ${idmap.filePath()} could not be read: ` +
        `${err.message}. Refusing to answer a user search from a map this shim cannot parse, ` +
        'because the alternative is handing CCRS a different person with HTTP 200.',
    });
    throw err;
  }

  if (!entry) {
    gaplog.record({
      dep: DEP,
      field: 'accountId -> individual.id',
      action: 'unmapped',
      detail:
        `The 2.x uuid ${uuid2x} is not in the durable map (${idmap.filePath()}, ` +
        `${(() => { try { return idmap.size(); } catch { return '?'; } })()} entries). It is sent ` +
        'to v3 UNCHANGED, which is what the stateless shim did: the individual registry has no ' +
        'record with that id and answers an empty list, so CCRS sees INVALID_ACCOUNTID ' +
        '(enrichUser), USER_NOT_FOUND (searchBulkUser) or a null role name -> assignee "NA" ' +
        '(getFirstRoleNameByUuid). NOTHING IS INVENTED HERE. Closing this for real means ' +
        'migrating every egov-user row into the individual registry and recording each ' +
        'correspondence - one map entry per person, not one per POC.',
    });
    return uuid2x;
  }

  if (tenantId && entry.tenantId && entry.tenantId !== tenantId) {
    // A map entry is a claim about a person in a tenant. Using one across a
    // tenant boundary is how a shim leaks one tenant's citizens into another.
    gaplog.record({
      dep: DEP,
      field: 'accountId -> individual.id',
      action: 'unmapped',
      detail:
        `The durable map holds ${uuid2x} under tenant ${JSON.stringify(entry.tenantId)} but the ` +
        `search arrived for tenant ${JSON.stringify(tenantId)}. The entry is NOT used and the ` +
        '2.x uuid is sent unchanged (which will find nothing). 2.x resolves user searches at the ' +
        'STATE-level tenant (UserService.searchUser passes getStateLevelTenant), while v3 ' +
        'resolves them at whatever X-Tenant-ID says, so the two tenancy models do not line up ' +
        'and the map cannot paper over that.',
    });
    return uuid2x;
  }

  gaplog.record({
    dep: DEP,
    field: 'accountId -> individual.id',
    action: 'faked',
    detail:
      `Translated the 2.x egov-user uuid ${uuid2x} into the v3 individual id ${entry.individualId} ` +
      `using the shim's durable map (${idmap.filePath()}, recorded ${entry.recordedAt} by ` +
      `${entry.source || 'unknown'}). THIS CORRESPONDENCE EXISTS NOWHERE ELSE: v3 silently ` +
      'discards a caller-supplied id on create and ignores the ?userId= filter, so neither ' +
      'registry can state that these two ids are the same person. The shim is asserting it. ' +
      'Every complaint created while this map is live carries a 2.x accountId whose only bridge ' +
      'to the person of record is this file - lose it and the complaints are orphans.',
  });
  return entry.individualId;
}



// Only THREE v3 filters are actually implemented by the deployed service:
// `id` (repeatable), `individualId` and `mobileNumber`. Everything else in the
// spec's parameter list - `userId`, `givenName`, and any unknown name at all -
// is ACCEPTED AND IGNORED: the service answers 200 with the whole tenant.
// Verified: ?userId=<bogus> -> totalCount 4 (every individual), ?givenName=Zzprobe
// -> 4, ?wibble=xyz -> 4, while the spec promises 400 for unknown parameters.
// So this translator uses ONLY the three filters it has watched work, and
// throws on any 2.x criterion it cannot express. Sending an ignored filter
// would hand CCRS the wrong person with HTTP 200.
function searchToV3(req2x) {
  const tenantId = tenantOf(req2x);
  const uuids = Array.isArray(req2x && req2x.uuid)
    ? req2x.uuid.filter((u) => typeof u === 'string' && u.length)
    : [];
  const userName = typeof req2x.userName === 'string' ? req2x.userName.trim() : '';
  const mobileNumber = typeof req2x.mobileNumber === 'string' ? req2x.mobileNumber.trim() : '';

  noteDroppedSearchFilters(req2x, tenantId);

  const params = [];
  let size = V3_MAX_SIZE;

  if (uuids.length) {
    if (uuids.length > V3_MAX_SIZE) {
      gaplog.record({
        dep: DEP,
        field: 'uuid[]',
        action: 'unmapped',
        detail:
          `A 2.x user search asked for ${uuids.length} uuids. v3 pages its individual search and ` +
          `refuses size>${V3_MAX_SIZE} (verified: ?size=200 -> 400), and the one-request-in / ` +
          'one-request-out translator contract cannot page. Answering the first ' +
          `${V3_MAX_SIZE} would make UserService.searchBulkUser silently attach a null citizen to ` +
          'every complaint past the page boundary, so the shim throws instead. Serving this needs ' +
          'either a larger page or cursor paging the shim is allowed to follow.',
      });
      throw new Error(
        `user translator: ${uuids.length} uuids requested, v3 individual search caps size at ${V3_MAX_SIZE}`
      );
    }
    // The repeated form, NOT the comma form: ?id=a,b answers 400.
    //
    // Every uuid arriving here is an egov-user row id (CCRS mints it at login
    // and stamps it on the complaint as accountId, EnrichmentService:64). v3
    // has never heard of it, and CANNOT be taught it - see idmap.js. So each
    // one is resolved through the durable map and the V3 INDIVIDUAL ID is sent
    // instead. An uuid the map does not hold is sent UNCHANGED, which is
    // precisely the pre-map behaviour: v3 answers "no such individual", the
    // caller gets the same honest empty list it got before, and no existing
    // call path (getFirstRoleNameByUuid asking about an employee, say) changes
    // shape because this file learned to translate the one citizen it knows.
    for (const u of uuids) params.push(`id=${encodeURIComponent(resolveUuid(u, tenantId))}`);
    size = Math.max(uuids.length, 1);
  } else if (userName || mobileNumber) {
    const value = mobileNumber || userName;
    if (userName && !mobileNumber) {
      gaplog.record({
        dep: DEP,
        field: 'userName',
        action: 'faked',
        detail:
          `A 2.x user search by userName=${JSON.stringify(userName)} was translated into the v3 ` +
          'individual filter ?mobileNumber=, because v3 has NO userName field at all (the create ' +
          'endpoint rejects one outright: 400 unknown field "userName"). The substitution is only ' +
          'sound for the call path that issues it: UserService.upsertUser:82 searches ' +
          "userName = the citizen's mobile number, and UserUtils.addUserDefaultFields:118 sets " +
          'userName = mobileNumber when creating one, so for CCRS citizens the two really are the ' +
          'same string. It is WRONG for any other kind of login - an employee whose userName is ' +
          '"PGGRO1" would be searched for as a mobile number and found to not exist. A real login ' +
          "name lives in Keycloak's user store, which this registry does not expose.",
      });
    }
    params.push(`mobileNumber=${encodeURIComponent(value)}`);
  } else {
    gaplog.record({
      dep: DEP,
      field: 'UserSearchRequest',
      action: 'unmapped',
      detail:
        'A 2.x user search arrived carrying none of uuid[], userName or mobileNumber - the only ' +
        'three criteria the v3 individual registry can actually filter on. Every other field of ' +
        'UserSearchRequest (id[], name, aadhaarNumber, pan, emailId, roleCodes, fuzzyLogic) has ' +
        'either no v3 counterpart or a counterpart the deployed service ignores. Translating it ' +
        'would mean asking v3 for the whole tenant and calling the answer a match, so the shim ' +
        'throws instead.',
    });
    throw new Error(
      'user translator: /user/_search carried no uuid, userName or mobileNumber - refusing to ' +
        'translate into an unfiltered v3 individual search'
    );
  }

  params.push(`size=${Math.min(size, V3_MAX_SIZE)}`);
  params.push('page=1');

  return {
    method: 'GET',
    path: `${V3_INDIVIDUALS}?${params.join('&')}`,
    headers: authHeaders(req2x, tenantId),
  };
}

// Everything CCRS asked us to filter on that v3 will not.
function noteDroppedSearchFilters(req2x, tenantId) {
  if (req2x.userType !== undefined && req2x.userType !== null) {
    gaplog.record({
      dep: DEP,
      field: 'userType',
      action: 'dropped',
      detail:
        `The 2.x search asked for userType=${JSON.stringify(req2x.userType)} and v3 CANNOT APPLY ` +
        'IT: the Individual schema has no user type, and the create endpoint rejects one (400 ' +
        'unknown field "type"). The filter is therefore dropped and the search SILENTLY WIDENS - ' +
        'an employee\'s individual record satisfies a CITIZEN-only search. pgr-services relies on ' +
        'this filter in four places (UserService.searchUser:167, searchBulkUser:195, ' +
        'enrichUserIds:277 all pin USERTYPE_CITIZEN). The real distinction lives in Keycloak: it ' +
        'is a realm role, not an attribute of the person.',
    });
  }
  if (req2x.active === false) {
    gaplog.record({
      dep: DEP,
      field: 'active',
      action: 'unmapped',
      detail:
        'The 2.x search asked for active=false. v3 has no such filter: its individual search ' +
        'returns active records and takes only includeDeleted (default false), which is a ' +
        'soft-delete flag, not 2.x\'s user-disabled flag. There is no way to ask v3 for the ' +
        'disabled users, so the shim throws rather than answer with the ACTIVE ones.',
    });
    throw new Error('user translator: v3 individual search cannot filter active=false');
  }
  if (req2x.active === true) {
    gaplog.record({
      dep: DEP,
      field: 'active',
      action: 'defaulted',
      detail:
        'The 2.x search asked for active=true; v3 has no `active` filter and its search returns ' +
        'non-soft-deleted records by default (includeDeleted=false). The two are NOT the same ' +
        'concept - 2.x `active` is an administrative disable, v3 `isActive` tracks soft deletion - ' +
        'but on a registry where nothing has been deleted they coincide, so no parameter is sent.',
    });
  }
  const unusable = ['id', 'name', 'aadhaarNumber', 'pan', 'emailId', 'roleCodes'];
  for (const f of unusable) {
    const v = req2x[f];
    const present = Array.isArray(v) ? v.length > 0 : v !== undefined && v !== null && v !== '';
    if (!present) continue;
    gaplog.record({
      dep: DEP,
      field: f,
      action: 'dropped',
      detail:
        `The 2.x search carried ${f}=${JSON.stringify(v)}. v3's individual search has no working ` +
        `filter for it (${f === 'name' ? 'the spec lists givenName, but the deployed service ' +
          'ACCEPTS AND IGNORES it - ?givenName=Zzprobe returned every individual in the tenant' :
          f === 'emailId' ? 'email is stored but is not a documented or working search filter' :
          f === 'roleCodes' ? 'roles do not exist in the individual registry at all; they are ' +
            'Keycloak realm roles' :
          f === 'id' ? 'CCRS\'s numeric egov-user primary key has no counterpart in a uuid-keyed ' +
            'registry' :
          'no such field exists in the v3 Individual schema'}), so it is not sent. The search ` +
        'runs on the remaining criteria only, which means it can return MORE rows than 2.x would.',
    });
  }
  if (Array.isArray(req2x.sort) && req2x.sort.length) {
    gaplog.record({
      dep: DEP,
      field: 'sort',
      action: 'dropped',
      detail:
        `The 2.x search asked for sort=${JSON.stringify(req2x.sort)} (UserSearchRequest defaults ` +
        'to ["name"]). v3\'s individual search has no sort parameter at all: the spec fixes the ' +
        'order at "creation time, newest first". For a search on a uuid list or an exact mobile ' +
        'number the order is irrelevant, which is every call pgr-services makes - but a UI that ' +
        'pages an alphabetical user list would silently get a different order.',
    });
  }
  if (Number(req2x.pageSize) > 0 || Number(req2x.pageNumber) > 0) {
    gaplog.record({
      dep: DEP,
      field: 'pageSize/pageNumber',
      action: 'dropped',
      detail:
        `The 2.x search carried pageSize=${JSON.stringify(req2x.pageSize)} / pageNumber=` +
        `${JSON.stringify(req2x.pageNumber)}. v3 pages with a 1-INDEXED page + size (max 100), ` +
        'while 2.x pageNumber is 0-indexed and pageSize=0 means "service default". The two ' +
        'schemes are off by one and the shim does not translate them: it asks for exactly the ' +
        'number of records the criteria imply. No pgr-services call site sets either field to a ' +
        'non-zero value today, so nothing is lost in practice - but a paging caller would be ' +
        'served the wrong page.',
    });
  }
  if (req2x.fuzzyLogic === true) {
    gaplog.record({
      dep: DEP,
      field: 'fuzzyLogic',
      action: 'dropped',
      detail:
        'The 2.x search asked for fuzzy matching. v3 individual search is exact-match only ' +
        '(the spec says so of mobileNumber explicitly), so the request quietly becomes an exact ' +
        'search. No pgr-services call site sets this today.',
    });
  }
  if (!tenantId) {
    gaplog.record({
      dep: DEP,
      field: 'tenantId',
      action: 'unmapped',
      detail:
        'The 2.x user search carried no tenantId anywhere (neither UserSearchRequest.tenantId nor ' +
        'RequestInfo.userInfo.tenantId). v3 requires X-Tenant-ID and answers 400 "Missing ' +
        'mandatory header: X-Tenant-ID" without it, so the call is going to fail; it is recorded ' +
        'here rather than guessed at, because guessing a tenant is how a shim leaks one tenant\'s ' +
        'citizens into another\'s.',
    });
  }
}

function searchToV2(v3Response, req2x) {
  const status = statusOf(v3Response);
  if (status >= 400) return errorEnvelope(v3Response, req2x, 'GET /individual/v3/individuals');

  const body = bodyOf(v3Response);
  const individuals = Array.isArray(body.individuals) ? body.individuals : [];
  const tenantId = tenantOf(req2x);
  const askedFor = Array.isArray(req2x && req2x.uuid) ? req2x.uuid.filter(Boolean) : [];

  if (askedFor.length && individuals.length < askedFor.length) {
    gaplog.record({
      dep: DEP,
      field: 'accountId -> individual.id',
      action: 'unmapped',
      detail:
        `A 2.x user search asked for ${askedFor.length} uuid(s) and the v3 individual registry ` +
        `returned ${individuals.length}. THIS IS THE IDENTITY-MIGRATION GAP, and it is the whole ` +
        'finding of Task 6. The uuids CCRS asks with are egov-user row ids, minted by CCRS\'s own ' +
        'login (POST /user/oauth/token -> UserRequest.uuid) and stamped onto every complaint by ' +
        'EnrichmentService.enrichCreateRequest:64 (service.accountId = requestInfo.userInfo.uuid). ' +
        'The v3 individual registry has no record with that id AND NO WAY TO BE GIVEN ONE: a ' +
        'create that supplies `id` has it silently replaced by a server-minted uuid (verified: ' +
        'sent aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee, got 629b9122-bc18-48d7-80fc-26731041e581, ' +
        '201), and the `userId` field that could have held the foreign key is accepted but its ' +
        'search filter is IGNORED by the deployed service (?userId=<bogus> returns every ' +
        'individual in the tenant, HTTP 200). CCRS will now raise INVALID_ACCOUNTID (enrichUser) ' +
        'or USER_NOT_FOUND (searchBulkUser). Closing this needs (1) a migration of every ' +
        'egov-user row into the individual registry, (2) a durable 2.x-uuid -> individual-id map, ' +
        'or an individual create that honours a caller-supplied id, and (3) sessions issued by ' +
        'the identity provider both sides trust - Keycloak (d3-keycloak is running and ' +
        'd3-individual has KEYCLOAK_ENABLED=true; CCRS\'s login does not use it). None of the ' +
        'three is a translation problem.',
    });
  }

  const users = individuals.map((ind) => mapIndividualToUser(ind, req2x, tenantId));
  restoreUuids(users, req2x);

  return {
    responseInfo: responseInfo(req2x, 'GET /individual/v3/individuals'),
    user: users,
  };
}

// The answer has to come back wearing the identity CCRS asked with, or the
// map only half works. pgr-services reads the uuid off this reply in three
// places: enrichUser:110 (non-empty is enough), enrichUsers:66 which does
// idToUserMap.get(service.getAccountId()) - a lookup keyed by the 2.x uuid
// already stored on the complaint - and upsertUser:97 which writes getUuid()
// back onto the complaint as the new accountId. Hand back a v3 individual id
// and the second silently attaches a NULL citizen to every complaint (HTTP
// 200, no error anywhere) and the third rewrites a good accountId into one
// egov-user cannot resolve. So the forward substitution above is undone here.
function restoreUuids(users, req2x) {
  for (const user of users) {
    if (!user || typeof user.uuid !== 'string' || !user.uuid) continue;
    let back = null;
    try {
      back = idmap.reverse(user.uuid);
    } catch (err) {
      gaplog.record({
        dep: DEP,
        field: 'idmap',
        action: 'unmapped',
        detail: `Reverse lookup of individual ${user.uuid} failed: ${err.message}`,
      });
      throw err;
    }
    if (!back) continue;
    gaplog.record({
      dep: DEP,
      field: 'user.uuid',
      action: 'faked',
      detail:
        `Answered with the 2.x uuid ${back.uuid2x} in place of the v3 individual id ${user.uuid}, ` +
        `from the durable map (${idmap.filePath()}). CCRS is therefore told the person still has ` +
        'the identity it has always used, which is the only way its own joins keep working ' +
        '(enrichUsers keys a map by accountId; upsertUser writes this value back onto the ' +
        'complaint). It is also a LIE ABOUT WHERE THE RECORD LIVES: nothing in the v3 registry ' +
        'carries this uuid, and every answer that passes through here is only as true as this ' +
        "shim's file. The honest end state is CCRS holding v3 ids, which means rewriting the " +
        'accountId column of every existing complaint - a data migration, not a translation.',
    });
    user.uuid = back.uuid2x;
  }
  return users;
}

// ---------------------------------------------------------------- 2. create

function createToV3(req2x) {
  const tenantId = tenantOf(req2x);
  const user = req2x && typeof req2x.user === 'object' && req2x.user ? req2x.user : {};

  const rawName = typeof user.name === 'string' ? user.name.trim() : '';
  const parts = rawName.split(/\s+/).filter(Boolean);
  const givenName = parts.length ? parts[0] : '';
  const familyName = parts.length > 1 ? parts.slice(1).join(' ') : undefined;

  if (rawName && parts.length > 1) {
    gaplog.record({
      dep: DEP,
      field: 'name',
      action: 'faked',
      detail:
        `CCRS's User has ONE name field (${JSON.stringify(rawName)}); v3's Individual has ` +
        'givenName + familyName + otherNames. The shim splits on the first space, which is a ' +
        'guess: it makes "Ada Lovelace King" a family name of "Lovelace King" and would mangle ' +
        'any culture whose family name comes first. Going back the other way the shim rejoins ' +
        'them with a space, so a round trip through v3 is lossy for anyone whose name does not ' +
        'happen to be two space-separated words.',
    });
  }
  if (givenName && !/^[A-Za-z ]+$/.test(givenName)) {
    gaplog.record({
      dep: DEP,
      field: 'givenName',
      action: 'unmapped',
      detail:
        `The 2.x user name ${JSON.stringify(rawName)} cannot be stored in v3: givenName is ` +
        'validated against a tenant-configured nameRegex defaulting to alphabets and spaces only, ' +
        'and the deployed service answers 400 "givenName must contain only alphabets and spaces" ' +
        '(verified with "Zz9 Probe."). 2.x egov-user accepts any string, so every CCRS citizen ' +
        'whose name holds an initial with a dot, a digit or a hyphen is unmigratable until the ' +
        "tenant's nameRegex is relaxed through POST /individual/v3/configs. The request is sent " +
        'anyway so the caller sees v3\'s own error rather than one this shim invented.',
    });
  }

  gaplog.record({
    dep: DEP,
    field: 'gender',
    action: 'faked',
    detail:
      `v3 REQUIRES gender on create - the deployed service answers 400 {"field":"gender",` +
      '"message":"gender is required"} without one, even though individual.yaml says "gender is ' +
      'optional" (spec and implementation disagree; the implementation wins). CCRS\'s ' +
      'web/models/User has NO gender field whatsoever, and neither does the PGR complaint that ' +
      `creates the citizen, so there is nothing to map from. Sent "${GENDER_FALLBACK}" ` +
      '(INDIVIDUAL_DEFAULT_GENDER). This is an invented demographic fact about a real person, ' +
      'written into a registry of record. The real value would have to be collected at ' +
      'registration - which means a CCRS UI change, not a shim change.',
  });

  gaplog.record({
    dep: DEP,
    field: 'roles[]',
    action: 'dropped',
    detail:
      `The 2.x create carried roles=${JSON.stringify(user.roles || [])} and type=` +
      `${JSON.stringify(user.type || null)} (UserUtils.addUserDefaultFields:116-120 stamps ` +
      'CITIZEN on both). The v3 Individual schema has NEITHER, and the service rejects them as ' +
      'unknown fields (400 unknown field "roles" / "type"), so they are removed from the request ' +
      'entirely. THE PERSON IS CREATED WITH NO AUTHORISATION AT ALL. In DIGIT 3.0 roles are ' +
      "Keycloak realm roles held against a Keycloak user - d3-individual is configured with " +
      'KEYCLOAK_ASSIGNABLE_ROLES=ASSIGNER,CITIZEN and KEYCLOAK_BASE_URL, i.e. the linkage exists ' +
      'as configuration - and the individual registry stores only the person. Creating a usable ' +
      'CCRS citizen on v3 therefore needs TWO writes to two services, which a one-request-in / ' +
      'one-request-out translator cannot perform.',
  });

  gaplog.record({
    dep: DEP,
    field: 'userName',
    action: 'dropped',
    detail:
      `The 2.x create carried userName=${JSON.stringify(user.userName || null)}; v3 rejects the ` +
      'field outright (400 unknown field "userName"). There is no login name in the individual ' +
      'registry - it belongs to Keycloak. On the way back the shim reports mobileNumber as the ' +
      'userName, which is true for CCRS citizens by convention and false in general.',
  });

  if (user.correspondenceAddress || user.countryCode) {
    gaplog.record({
      dep: DEP,
      field: 'correspondenceAddress/countryCode',
      action: 'dropped',
      detail:
        "CCRS's User carries a single free-text correspondenceAddress and a countryCode. v3's " +
        'Individual has a structured address[] (type/doorNo/street/city/pincode/lat/lng, each ' +
        'address needing at least one of doorNo/street/landmark/city) and no country code on the ' +
        'phone number. A free-text address cannot be split into those fields without parsing ' +
        'guesswork, so it is not sent at all.',
    });
  }

  const body = { givenName, gender: GENDER_FALLBACK };
  if (familyName) body.familyName = familyName;
  if (user.mobileNumber) body.mobileNumber = String(user.mobileNumber);
  if (user.emailId) body.email = String(user.emailId);

  return {
    method: 'POST',
    path: V3_INDIVIDUALS,
    headers: authHeaders(req2x, tenantId),
    body,
  };
}

function createToV2(v3Response, req2x) {
  const status = statusOf(v3Response);
  if (status >= 400) return errorEnvelope(v3Response, req2x, 'POST /individual/v3/individuals');

  const ind = bodyOf(v3Response);
  const tenantId = tenantOf(req2x);
  const requested = req2x && typeof req2x.user === 'object' && req2x.user ? req2x.user : {};

  gaplog.record({
    dep: DEP,
    field: 'user.uuid',
    action: 'faked',
    detail:
      `A citizen created through v3 comes back with a SERVER-MINTED uuid (${ind.id || 'null'}) ` +
      'that exists only in the individual registry. pgr-services immediately writes it onto the ' +
      'complaint as accountId (UserService.upsertUser:97). That complaint is then unreadable by ' +
      'any 2.x user lookup, and the citizen cannot log in to see it: CCRS logins are served by ' +
      'egov-user, which has no such row. Every complaint created this way is a new orphan. This ' +
      'is the same identity split as the search gap, seen from the write side.',
  });

  return {
    responseInfo: responseInfo(req2x, 'POST /individual/v3/individuals'),
    user: [mapIndividualToUser(ind, req2x, tenantId, requested)],
  };
}

// ---------------------------------------------------------------- 3. update

function updateToV3(req2x) {
  const user = req2x && typeof req2x.user === 'object' && req2x.user ? req2x.user : {};
  gaplog.record({
    dep: DEP,
    field: UPDATE_2X,
    action: 'unmapped',
    detail:
      'CCRS called /user/users/_updatenovalidate (UserService.updateUser:155 renames a citizen ' +
      'whose name changed, updateUserContactDetails:261 writes back an email or address). The v3 ' +
      'counterpart PUT /individual/v3/individuals/{id} is an OPTIMISTIC-CONCURRENCY API: `version` ' +
      'is mandatory and a request without one is refused with 409 ROW_VERSION_MISMATCH ' +
      '{"expected":1,"provided":0} - verified live, twice (no version -> 409, version:1 -> 200). ' +
      'The 2.x User payload carries no version and never has, so the shim would have to GET the ' +
      'individual first and then PUT it - TWO v3 calls for one 2.x call, which the translator ' +
      'contract {toV3 -> one request, toV2 -> one response} cannot express. It is refused rather ' +
      'than faked: guessing version:1 would silently overwrite a concurrent edit, and the whole ' +
      'point of the field is to stop that. ' +
      `(uuid=${JSON.stringify(user.uuid || null)}). Serving this needs either a conditional-free ` +
      'update, an If-Match header the caller can omit, or a stateful shim that caches versions.',
  });
  const err = new Error(
    'user translator: /user/users/_updatenovalidate cannot be served - v3 PUT ' +
      '/individual/v3/individuals/{id} requires the record `version`, which the 2.x payload does ' +
      'not carry and which can only be learned by a second v3 call'
  );
  err.shimUnmapped = true;
  throw err;
}

// ---------------------------------------------------------------- mapping

// v3 Individual -> CCRS User. CCRS needs id, uuid, userName, mobileNumber,
// type, roles[] and tenantId; v3 supplies exactly two of those seven.
// `requested` is supplied ONLY on the create path, where it is the User object
// CCRS just sent us. There it is an ECHO - reporting back what the caller
// asserted, which v3 accepted and discarded. On the SEARCH path there is no
// such caller assertion, so nothing is echoed and nothing is invented: roles
// come back EMPTY and type comes back NULL. See the roles gap entry for why
// that is the right answer rather than a stamped "CITIZEN".
function mapIndividualToUser(ind, req2x, tenantId, requested) {
  const src = ind && typeof ind === 'object' ? ind : {};
  const isEcho = !!(requested && typeof requested === 'object');
  const want = isEcho ? requested : {};

  // The live service returns flat givenName/familyName. The task brief's own
  // contract test supplies a nested {name:{givenName}}, so both are read.
  const nested = src.name && typeof src.name === 'object' ? src.name : {};
  const givenName = src.givenName || nested.givenName || '';
  const familyName = src.familyName || nested.familyName || '';
  const name = [givenName, familyName].filter(Boolean).join(' ') || null;

  const mobileNumber = src.mobileNumber === undefined ? null : src.mobileNumber;
  const audit = src.auditDetail && typeof src.auditDetail === 'object' ? src.auditDetail : {};

  gaplog.record({
    dep: DEP,
    field: 'user.id',
    action: 'dropped',
    detail:
      "CCRS's User.id is a Long - the egov-user table's primary key - and several 2.x flows key " +
      'off it (MigrationService:266 parses it to look up a uuid). The v3 individual registry is ' +
      'uuid-keyed and has no numeric id, so the field is emitted as null rather than invented. ' +
      'A fabricated number would collide with a real egov-user row the moment anything joined on ' +
      'it. `individualId` (e.g. IND-f61cafe2) is v3\'s human-facing id but it is a string and ' +
      'means something else.',
  });

  if (isEcho) {
    gaplog.record({
      dep: DEP,
      field: 'user.type',
      action: 'faked',
      detail:
        `Create path: echoed type="${want.type || null}" straight back from the CreateUserRequest. ` +
        "v3's Individual has NO user type - the field is REJECTED on create as an unknown field - " +
        'so this is not read from the answer, it is the caller\'s own assertion handed back. ' +
        'The person now exists in the registry with no type recorded anywhere. In DIGIT 3.0 the ' +
        'CITIZEN/EMPLOYEE distinction is a Keycloak realm role (d3-individual: ' +
        'KEYCLOAK_ASSIGNABLE_ROLES=ASSIGNER,CITIZEN), so a migration must create the Keycloak user ' +
        'alongside the individual - two writes to two services, which a one-request-in / ' +
        'one-request-out translator cannot perform.',
    });
  } else {
    gaplog.record({
      dep: DEP,
      field: 'user.type',
      action: 'dropped',
      detail:
        'Search path: type emitted as NULL, because v3 does not report one and the shim will not ' +
        'invent one. The v3 Individual schema has no user type at all (it is REJECTED on create as ' +
        'an unknown field), so any value here would be fabricated. An earlier revision stamped ' +
        '"CITIZEN" on every individual a search returned, which would have labelled EMPLOYEES as ' +
        'citizens; that is now fixed. ' +
        'BLAST RADIUS OF THE NULL, traced exhaustively: NOTHING in pgr-services branches on the ' +
        'type of a user returned by the user service. Every `.getType()` call site - ' +
        'Principals.isPureCitizen:66, EmployeeContextService:69, PolicyInputBuilder:48, ' +
        'ServiceRequestValidator:133/245/290-307, EnrichmentService:63 - reads ' +
        'org.egov.common.contract.request.User, i.e. RequestInfo.userInfo, which the GATEWAY fills ' +
        'from the 2.x token and which EGOV_USER_HOST does not move. The shim answers into ' +
        'org.egov.pgr.web.models.User, a different class, whose `type` is serialised onto ' +
        'service.citizen and never read. ' +
        'THE REAL MIGRATION REQUIREMENT: user type must be sourced from Keycloak realm roles.',
    });
  }

  const roles = isEcho && Array.isArray(want.roles) && want.roles.length ? want.roles : [];
  if (isEcho) {
    gaplog.record({
      dep: DEP,
      field: 'user.roles[]',
      action: 'faked',
      detail:
        `Create path: echoed roles=${JSON.stringify(roles)} straight back from the ` +
        'CreateUserRequest (UserUtils.addUserDefaultFields:116 stamps CITIZEN before sending). ' +
        'NOTHING IN v3 REPORTED THIS: the individual registry has no roles field and REJECTS one ' +
        'on create (400 unknown field "roles"), so the person is created with no authorisation at ' +
        'all. The echo is harmless here - UserService.upsertUser:97 reads only .getUuid() from ' +
        'this reply - but it is still the caller hearing its own assertion back. Real roles are ' +
        'Keycloak realm roles against a Keycloak user linked to the individual.',
    });
  } else {
    gaplog.record({
      dep: DEP,
      field: 'user.roles[]',
      action: 'dropped',
      detail:
        'Search path: roles emitted as an EMPTY LIST, because v3 does not report them and the shim ' +
        'will not invent them. The v3 Individual schema has no roles field and REJECTS one on ' +
        'create (400 unknown field "roles"). An earlier revision stamped ' +
        '[{code:"CITIZEN",name:"Citizen"}] on every individual a search returned, which meant ' +
        'UserService.getFirstRoleNameByUuid(<an employee uuid>) answered "Citizen"; that is now ' +
        'fixed. ' +
        'BLAST RADIUS OF THE EMPTY LIST, traced exhaustively rather than assumed: there is exactly ' +
        'ONE consumer of roles on a user the USER SERVICE returned - ' +
        'UserService.getFirstRoleNameByUuid:241, whose single caller is ' +
        'ComplaintDomainEventService.getAssigneeName:226, a DISPLAY STRING on an outbound domain ' +
        'event. It is null-tolerant on both hops (getFirstRoleNameByUuid returns null for an empty ' +
        'list; getAssigneeName falls back to "NA"), so the effect is a domain event reading ' +
        'assignee "NA" instead of a role name. ' +
        'THIS IS NOT A PRIVILEGE PATH, and an earlier revision of this entry wrongly implied it ' +
        'was. Every authorisation site reads org.egov.common.contract.request.User from ' +
        'RequestInfo.userInfo - PGRService.hasAnyRole:438-442 (behind ' +
        'isAuthorizedForConfidential:531, the confidential-field masking check), ' +
        'Principals.isPureCitizen:55, RoleCodes.normalize:29 (MDMSUtils:809, ' +
        'AnalyticsCapabilityService:43, AccessPolicyRegistry:493, PolicyDrivenScopeResolver:171 ' +
        'via requestInfo.getUserInfo() at :86), EmployeeContextService:154, PolicyInputBuilder:27. ' +
        'That object is filled by the GATEWAY from the 2.x token, and the gateway validates ' +
        'sessions through its own upstream, NOT through EGOV_USER_HOST - so the shim response can ' +
        'never reach a privilege decision. It is a different Java class (the shim answers into ' +
        'org.egov.pgr.web.models.User) and it is structurally unable to get there. ' +
        'THE REAL MIGRATION REQUIREMENT stands: CCRS roles must be sourced from Keycloak realm ' +
        'roles, which needs the Keycloak admin API, its own credentials, and a ' +
        '2.x-role -> realm-role mapping nobody has written.',
    });
  }

  gaplog.record({
    dep: DEP,
    field: 'user.tenantId',
    action: 'defaulted',
    detail:
      `Emitted tenantId=${JSON.stringify(tenantId || null)}, taken from the REQUEST (the ` +
      'X-Tenant-ID the shim searched with). v3 resolves the tenant from that header and does not ' +
      'echo it in the individual body at all, so this is the question being reported as the ' +
      'answer. It is right by construction for a single-tenant search and would be wrong the ' +
      'moment v3 gained a cross-tenant one.',
  });

  gaplog.record({
    dep: DEP,
    field: 'user.userName',
    action: 'faked',
    detail:
      `Emitted userName=${JSON.stringify(mobileNumber)} - the individual's mobile number, ` +
      'because v3 has no userName. True for CCRS citizens by convention ' +
      '(UserUtils.addUserDefaultFields:118 sets userName = mobileNumber) and false for every ' +
      'other kind of login, including the employees this same endpoint is asked about by ' +
      'UserService.getFirstRoleNameByUuid. The login name is Keycloak\'s, not the registry\'s.',
  });

  gaplog.record({
    dep: DEP,
    field: 'user.createdDate',
    action: 'faked',
    detail:
      `Emitted createdDate as "${DATE_FMT_2X}" (UTC) derived from ` +
      `auditDetail.createdTime=${audit.createdTime === undefined ? 'ABSENT' : audit.createdTime}. ` +
      'This field is NOT optional: UserUtils.parseResponse:79 calls ' +
      'dateTolong(map.get("createdDate"), "dd-MM-yyyy HH:mm:ss") on every user in the response ' +
      'with no null check, so omitting it makes pgr-services throw a NullPointerException inside ' +
      'SimpleDateFormat.parse(). v3 reports epoch millis and no timezone, while 2.x egov-user ' +
      'formats in the server\'s local zone, so a round trip through the shim shifts the value by ' +
      'the host offset. When v3 reports no audit block at all the shim substitutes NOW, which is ' +
      'a lie about when the person was registered - unavoidable, because the alternative is a 500.',
  });

  const user = {
    id: null,
    uuid: src.id === undefined ? null : src.id,
    userName: mobileNumber,
    name,
    type: isEcho ? (want.type === undefined ? null : want.type) : null,
    mobileNumber,
    emailId: src.email === undefined ? null : src.email,
    roles,
    tenantId: tenantId || null,
    active: src.isActive === undefined ? true : !!src.isActive,
    createdDate: formatDate2x(audit.createdTime),
  };
  if (audit.modifiedTime !== undefined) {
    user.lastModifiedDate = formatDate2x(audit.modifiedTime);
  }
  return user;
}

// A v3 error must reach CCRS as an error, never as an empty user list: an
// empty list is a MEANINGFUL answer to a user search ("no such person") and
// CCRS acts on it.
function errorEnvelope(v3Response, req2x, which) {
  const body = bodyOf(v3Response);
  const raw = v3Response && typeof v3Response.raw === 'string' ? v3Response.raw : '';
  let list = [];
  if (Array.isArray(v3Response && v3Response.json)) list = v3Response.json;
  else if (Array.isArray(body.errors)) list = body.errors;
  else if (body.code || body.message) list = [body];

  const errors = list.length
    ? list.map((e) => ({
        code: e && e.code ? e.code : 'V3_ERROR',
        message: e && e.message ? e.message : String(raw).slice(0, 500),
        description: e && e.description ? e.description : null,
        params: e && e.params ? e.params : null,
      }))
    : [{ code: 'V3_ERROR', message: String(raw).slice(0, 500) || `v3 ${which} failed`, description: null, params: null }];

  gaplog.record({
    dep: DEP,
    field: 'Errors',
    action: 'faked',
    detail:
      `v3 ${which} answered HTTP ${statusOf(v3Response)} with ` +
      `${JSON.stringify(errors).slice(0, 400)}. DIGIT 3.0 returns a BARE ARRAY of ` +
      '{code,message,description,params}; 2.x expects {ResponseInfo, Errors:[...]} with ' +
      'id/parentId fields the v3 error does not have. The shim rewraps it and keeps the upstream ' +
      'status code so pgr-services\' RestTemplate raises rather than parses an empty user list - ' +
      'an empty list is a MEANINGFUL answer to a user search ("no such person") and CCRS acts on ' +
      'it, so a failed call must never look like one.',
  });

  return { ResponseInfo: responseInfo(req2x, which), Errors: errors };
}

// ---------------------------------------------------------------- dispatch

// There is NO body-shape fallback, matching workflow.js. An earlier version
// dispatched on the body when no path was present ("a `user` object means a
// create, otherwise a search"), so that a unit test could call the translator
// directly - and the cost of that affordance was that an unrecognised call
// would have been served as a user SEARCH and answered {responseInfo,
// user: []}. An empty user list is not a neutral result here: it is the
// MEANINGFUL answer "no such person", and CCRS acts on it (INVALID_ACCOUNTID,
// or a silently null citizen). That is the Task 4 failure mode - a plausible
// empty 200 - kept alive in production code for the convenience of tests.
//
// It is safe to remove: server.js sets __shim unconditionally in makeReq2x and
// server.js:145/153 are the only call sites of any translator, so no live
// caller can arrive without it (and the gap log records no such arrival). The
// tests construct a __shim of their own.
//
// `hint` is NOT that fallback and stays: it carries a real inbound path as a
// second argument, which is how the task brief's own contract tests call this.
function toV3(req2x, hint) {
  const target = withHint(req2x, hint);
  const route = routeOf(target);
  if (route === 'search') return searchToV3(target);
  if (route === 'create') return createToV3(target);
  if (route === 'update') return updateToV3(target);
  return unknownRoute(target);
}

function toV2(v3Response, req2x) {
  const route = routeOf(req2x);
  if (route === 'search') return searchToV2(v3Response, req2x);
  if (route === 'create') return createToV2(v3Response, req2x);
  if (route === 'update') return updateToV3(req2x); // always throws
  return unknownRoute(req2x);
}

// The task brief's contract tests call toV3(body, {path}) with the path as a
// SECOND argument rather than on req2x.__shim. Support it without letting the
// hint leak into anything that gets re-serialised.
function withHint(req2x, hint) {
  if (!hint || typeof hint !== 'object' || !hint.path) return req2x;
  if (!req2x || typeof req2x !== 'object') return req2x;
  if (pathOf(req2x)) return req2x;
  try {
    Object.defineProperty(req2x, '__path', {
      value: hint.path,
      enumerable: false,
      configurable: true,
    });
  } catch {
    /* frozen object: fall through and dispatch on shape */
  }
  return req2x;
}

module.exports = {
  toV3,
  toV2,
  dep: DEP,
  // exported for focused unit tests
  searchToV3,
  searchToV2,
  createToV3,
  createToV2,
  updateToV3,
  mapIndividualToUser,
  formatDate2x,
};
