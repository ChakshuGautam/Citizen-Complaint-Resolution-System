// src/translators/boundary.js
//
// CCRS (DIGIT 2.12) boundary-service  ->  DIGIT 3.0 boundary.
//
// =====================================================================
// OBSERVED CONTRACTS (probed live on this box 2026-09-20, not guessed)
// =====================================================================
//
// EGOV_BOUNDARY_HOST serves TWO different 2.x endpoints, not one, and both
// move when the env var moves. Missing the second one is not hypothetical:
// the first switchover attempt registered a single translator at the
// /boundary-service prefix, so the relationship call was answered with a
// BOUNDARY envelope, pgr-services found no "TenantBoundary" key, expanded the
// employee's jurisdiction to the empty set, and the final employee search
// returned zero complaints - 200 OK, no error anywhere. See the gap log.
//
//   1. /boundary-service/boundary/_search
//      ServiceRequestValidator.validateBoundary - "is this locality real?"
//   2. /boundary-service/boundary-relationships/_search
//      BoundaryHierarchyExpander.fetch - "which boundary codes may this
//      employee see?", used to filter the employee complaint search.
//
// --- 1. boundary search, 2.x ----------------------------------------
// The URL is built by STRING CONCATENATION and the body is the BARE
// RequestInfo - NOT {"RequestInfo":{...}}:
//
//   POST {egov.boundary.host}{egov.boundary.search.url}
//        ?limit=10&offset=0&tenantId=<service.tenantId>&codes=<locality.code>
//   body: the RequestInfo object itself.
//
// So req2x.userInfo.uuid is at the TOP level and req2x.RequestInfo does not
// exist. context.toHeaders() therefore yields an empty X-Tenant-ID here, and
// this translator supplies the headers itself.
//
//   egov.boundary.host       = http://boundary-service:8081/   (trailing /)
//   egov.boundary.search.url = /boundary-service/boundary/_search (leading /)
//   -> the concatenation contains a DOUBLE slash, but Spring's RestTemplate
//      normalises it before the wire: boundary-service's TracerFilter logs
//      "Received request URI: .../boundary-service/boundary/_search" with a
//      single slash. Verified in the live container log; the double-slash form
//      is actually rejected by 2.x itself with a 500 ("Invalid contextPath
//      '/boundary-service'"). So the shim sees a single slash and the
//      registered prefix matches.
//
// Live 2.x reply (POST to the real boundary-service at 172.19.0.22:8081):
//   {"ResponseInfo":{"apiId":"Rainmaker","ver":".01","ts":null,
//                    "resMsgId":"uief87324","msgId":"t4","status":"successful"},
//    "Boundary":[{"id":"8cf59d36-...","tenantId":"pg","code":"SUN01_LOCALITY",
//                 "geometry":{"type":"Point","coordinates":[74.87155,31.63089]},
//                 "auditDetails":{"createdBy":"system","lastModifiedBy":"system",
//                                 "createdTime":...,"lastModifiedTime":...},
//                 "additionalDetails":{}}]}
//
//   NOTE the CAPITAL "Boundary" / "ResponseInfo" - BoundaryResponse.java
//   declares @JsonProperty("Boundary") / @JsonProperty("ResponseInfo").
//   A miss is 200 with "Boundary":[]; CCRS turns that into
//   INVALID_BOUNDARY_CODE itself, so an empty list is a legitimate answer and
//   must NOT be faked into a hit.
//
// --- 1. boundary search, v3 -----------------------------------------
//   GET /boundary/v3/boundaries?codes=<code>[&limit=&offset=]
//     headers: X-Tenant-ID REQUIRED (400 "Missing X-Tenant-Id header").
//              The tenant is a HEADER dimension in v3; a stray ?tenantId= is
//              accepted and ignored.
//     200: {"boundary":[{"id":"...","tenantId":"pg","code":"SUN01_LOCALITY",
//            "geometry":{...},"additionalAttributes":{},
//            "auditDetails":{"createdBy":"...","modifiedBy":"...",
//                            "createdTime":...,"modifiedTime":...}}]}
//     400: [{"code":"BAD_REQUEST","message":"Missing required query parameter:
//            codes",...}]   <- a bare ARRAY, no envelope
//     A code that does not exist, or the right code under the wrong tenant, is
//     200 with "boundary":[] - same shape as a 2.x miss.
//
// --- 2. relationship search, 2.x ------------------------------------
// BoundaryHierarchyExpander.fetch, and this one DOES wrap the RequestInfo:
//
//   POST {host}/boundary-service/boundary-relationships/_search
//        ?tenantId=pg&hierarchyType=ADMIN&codes=PG_STATE&includeChildren=true
//   body: {"RequestInfo":{...}}
//
// Live 2.x reply:
//   {"ResponseInfo":{...},
//    "TenantBoundary":[{"tenantId":"pg","hierarchyType":"ADMIN",
//      "boundary":[{"id":"...","code":"PG_STATE","boundaryType":"State",
//                   "children":[{...,"children":[...]}]}]}]}
//
//   NOTE the CAPITAL "TenantBoundary". Nodes are exactly
//   {id, code, boundaryType, children} - no auditDetails - and a LEAF still
//   carries "children":[]. The expander reads only `code` and `children`,
//   recursively, into a Set, and on ANY failure it logs a warning and falls
//   back to the single unexpanded code. That silent fallback is why a broken
//   translation here shows up as an empty search result rather than an error.
//
// --- 2. relationship search, v3 -------------------------------------
//   GET /boundary/v3/relationship?hierarchyType=ADMIN&codes=PG_STATE
//       [&includeChildren=true]
//     200: {"tenantBoundary":[{"tenantId":"pg","hierarchyType":"ADMIN",
//            "boundary":[{"id":"...","code":"PG_STATE","boundaryType":"State",
//              "children":[...],"auditDetails":{...}}]}]}
//     Only the envelope key case differs ("tenantBoundary" vs
//     "TenantBoundary"); the tree shape is the same. includeChildren behaves
//     the same way as 2.x: without it, only the root node comes back.
//
// --- what does not cross the boundary -------------------------------
//   * KEY CASE, twice: v3 "boundary"/"tenantBoundary" vs 2.x
//     "Boundary"/"TenantBoundary".
//   * MULTIPLE CODES DO NOT WORK IN v3 boundary search. 2.x
//     ?codes=PG_CITYA,SUN01_LOCALITY returns BOTH (verified live). v3 returns
//     [] for the same string, and also for the repeated-parameter form
//     ?codes=A&codes=B, even though boundary.yaml declares codes as
//     "type: array, style: form, explode: false" - i.e. exactly the
//     comma-separated form. The v3 implementation matches the raw string as
//     ONE code. CCRS only ever sends one, so the lifecycle is unaffected.
//   * tenantId moves from the query string to the X-Tenant-ID header.
//   * additionalAttributes (v3) vs additionalDetails (2.x) - renamed.
//   * auditDetails.modifiedBy/modifiedTime (v3) vs
//     lastModifiedBy/lastModifiedTime (2.x) - renamed on boundary search,
//     dropped on relationship search because 2.x emits no auditDetails there.
//   * ResponseInfo has no v3 equivalent; it is fabricated from the inbound
//     RequestInfo, exactly as the idgen translator does.
//   * 2.x returns the relationship root tree TWICE (one copy per matching
//     relationship row); v3 returns it once. The expander dedupes into a Set
//     so neither CCRS nor this shim depends on the duplication.
// =====================================================================

const gaplog = require('../gaplog');

const DEP = 'boundary-service';

const BOUNDARY_SEARCH = '/boundary-service/boundary/_search';
const RELATIONSHIP_SEARCH = '/boundary-service/boundary-relationships/_search';

const V3_BOUNDARIES = '/boundary/v3/boundaries';
const V3_RELATIONSHIP = '/boundary/v3/relationship';

// ---------------------------------------------------------------- helpers

// The query string reaches a translator two ways: `__query`, set directly on
// req2x by server.js, and `__shim.query` from the Task 2 request contract.
// They are the same object; accept either.
function queryOf(req2x) {
  if (!req2x || typeof req2x !== 'object') return {};
  if (req2x.__query && typeof req2x.__query === 'object') return req2x.__query;
  if (req2x.__shim && req2x.__shim.query && typeof req2x.__shim.query === 'object') {
    return req2x.__shim.query;
  }
  return {};
}

// validateBoundary posts a BARE RequestInfo; BoundaryHierarchyExpander wraps
// it. Accept both rather than betting on one.
function requestInfoOf(req2x) {
  if (!req2x || typeof req2x !== 'object') return {};
  if (req2x.RequestInfo && typeof req2x.RequestInfo === 'object') return req2x.RequestInfo;
  return req2x;
}

// Tenant is never hardcoded: the query string is what CCRS actually drives the
// search with, so it wins; the RequestInfo userInfo is the fallback.
function tenantOf(req2x) {
  const q = queryOf(req2x);
  const ri = requestInfoOf(req2x);
  const ui = ri.userInfo || {};
  return q.tenantId || ui.tenantId || ri.tenantId || '';
}

function userIdOf(req2x) {
  const ui = requestInfoOf(req2x).userInfo || {};
  return ui.uuid || '';
}

// Which of the two 2.x endpoints is this? Registering one prefix and guessing
// is what broke the first switchover, so an unrecognised path is a hard error
// and a gap-log entry, never a best-effort translation.
function routeOf(req2x) {
  const shim = req2x && req2x.__shim;
  const path = (shim && shim.path) || (req2x && req2x.__path) || '';
  const bare = String(path).split('?')[0].replace(/\/+$/, '') || String(path);
  if (bare.endsWith('/boundary/_search')) return 'boundary';
  if (bare.endsWith('/boundary-relationships/_search')) return 'relationship';
  return null;
}

function unknownRoute(req2x) {
  const shim = req2x && req2x.__shim;
  const hasContext = Boolean(shim && shim.path);
  const path = hasContext ? shim.path : '(no path: req2x carried no __shim)';
  gaplog.record({
    dep: DEP,
    field: path,
    action: 'unmapped',
    detail: hasContext
      ? `EGOV_BOUNDARY_HOST now points at the shim, and CCRS called ${path}, which is neither ` +
        `${BOUNDARY_SEARCH} nor ${RELATIONSHIP_SEARCH} - the only two 2.x boundary endpoints this ` +
        'translator has a verified v3 counterpart for. Refusing to guess: answering a boundary ' +
        'envelope to an unknown boundary call is exactly the failure that made the first switchover ' +
        'return zero complaints with no error. The shim throws, which surfaces as 502 SHIM_ERROR.'
      : 'A request reached the boundary translator with no __shim transport context, so there is ' +
        'no inbound path to dispatch on. server.js sets __shim on EVERY request (makeReq2x), so ' +
        'this cannot happen on a live path - it means the translator was called directly, by a ' +
        'test or by future code. Refusing to fall back to the boundary search as a default: that ' +
        'default would have answered an unknown call with a {ResponseInfo, Boundary: []} envelope ' +
        'carrying NO TenantBoundary key, which BoundaryHierarchyExpander.fetch turns into an empty ' +
        'jurisdiction and a zero-complaint employee inbox - HTTP 200, no error. That is the Task 4 ' +
        'failure mode exactly. Throwing instead.',
  });
  const err = new Error(
    hasContext
      ? `boundary translator: unsupported 2.x boundary path ${path} ` +
        `(expected ${BOUNDARY_SEARCH} or ${RELATIONSHIP_SEARCH})`
      : 'boundary translator: req2x carried no __shim transport context, so the inbound 2.x path ' +
        'is unknown and no route can be chosen. server.js always sets it; a direct caller must ' +
        `supply {__shim:{path}} naming one of ${BOUNDARY_SEARCH} or ${RELATIONSHIP_SEARCH}.`
  );
  err.shimUnmapped = true;
  throw err;
}

// Fabricated, exactly like the idgen translator: v3 has no ResponseInfo.
// The constants are copied from the live 2.x boundary-service reply.
function responseInfo(req2x, which) {
  const ri = requestInfoOf(req2x);
  gaplog.record({
    dep: DEP,
    field: 'ResponseInfo',
    action: 'faked',
    detail:
      `v3 ${which} returns its payload and nothing else - there is no ResponseInfo envelope ` +
      'anywhere in DIGIT 3.0. The 2.x ResponseInfo is reconstructed from the inbound RequestInfo ' +
      '(apiId/ver/msgId echoed) with ts=null, resMsgId="uief87324" and status="successful" copied ' +
      'verbatim from the live 2.x boundary-service reply. Nothing in it is reported by v3; every ' +
      'value is either echoed from the request or a constant.',
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

// Accept either the Task 2 client envelope {status, json, ...} or a bare v3
// body, so the translator can be unit-tested without standing up a client.
function bodyOf(v3Response) {
  if (!v3Response || typeof v3Response !== 'object') return {};
  if ('json' in v3Response || 'status' in v3Response || 'raw' in v3Response) {
    return v3Response.json && typeof v3Response.json === 'object' ? v3Response.json : {};
  }
  return v3Response;
}

// ---------------------------------------------------------------- 1. boundary search

function boundaryToV3(req2x) {
  const q = queryOf(req2x);
  const tenantId = tenantOf(req2x);
  const codes = q.codes === undefined || q.codes === null ? '' : String(q.codes);

  if (!tenantId) {
    gaplog.record({
      dep: DEP,
      field: 'tenantId',
      action: 'unmapped',
      detail:
        'No tenant could be derived from the 2.x boundary search: neither ?tenantId= nor the bare ' +
        'RequestInfo userInfo.tenantId was present. v3 requires X-Tenant-ID and answers 400 ' +
        '"Missing X-Tenant-Id header" without it, so the call is sent as-is and fails loudly rather ' +
        'than being silently defaulted to a tenant nobody asked for.',
    });
  }

  if (codes.includes(',')) {
    gaplog.record({
      dep: DEP,
      field: 'codes',
      action: 'unmapped',
      detail:
        `2.x boundary search was asked for MULTIPLE codes (${JSON.stringify(codes)}) and v3 cannot ` +
        'serve that. Verified live: 2.x ?codes=PG_CITYA,SUN01_LOCALITY returns both boundaries; v3 ' +
        'GET /boundary/v3/boundaries with the same comma-separated string returns {"boundary":[]}, ' +
        'and so does the repeated-parameter form ?codes=A&codes=B - even though boundary.yaml ' +
        'declares codes as "type: array, style: form, explode: false". The v3 implementation matches ' +
        'the raw string as a single code. The request is passed through unchanged so the caller gets ' +
        'an honest empty result, not a fabricated one. Serving this faithfully would need one v3 call ' +
        'per code, which the one-request-in/one-request-out translator contract cannot express.',
    });
  }

  const params = new URLSearchParams();
  if (codes) {
    params.set('codes', codes);
  } else {
    gaplog.record({
      dep: DEP,
      field: 'codes',
      action: 'unmapped',
      detail:
        'The 2.x boundary search carried no ?codes=. 2.x treats codes as optional and returns every ' +
        'boundary for the tenant (verified live: 7 rows for pg); v3 makes codes REQUIRED and answers ' +
        '400 "Missing required query parameter: codes". There is no v3 list-the-whole-tenant call on ' +
        "this endpoint, so the request is forwarded without codes and v3's 400 is passed through. " +
        "CCRS's validateBoundary always sends exactly one code, so this does not arise in the " +
        'complaint lifecycle.',
    });
  }
  if (q.limit !== undefined && q.limit !== null && String(q.limit) !== '') params.set('limit', String(q.limit));
  if (q.offset !== undefined && q.offset !== null && String(q.offset) !== '') params.set('offset', String(q.offset));

  const qs = params.toString();
  return {
    method: 'GET',
    path: qs ? `${V3_BOUNDARIES}?${qs}` : V3_BOUNDARIES,
    body: undefined,
    headers: { 'X-Tenant-ID': tenantId, 'X-User-ID': userIdOf(req2x) },
  };
}

function mapAuditDetails(v3Audit) {
  if (!v3Audit || typeof v3Audit !== 'object') return v3Audit;
  const out = {};
  if ('createdBy' in v3Audit) out.createdBy = v3Audit.createdBy;
  if ('modifiedBy' in v3Audit) out.lastModifiedBy = v3Audit.modifiedBy;
  else if ('lastModifiedBy' in v3Audit) out.lastModifiedBy = v3Audit.lastModifiedBy;
  if ('createdTime' in v3Audit) out.createdTime = v3Audit.createdTime;
  if ('modifiedTime' in v3Audit) out.lastModifiedTime = v3Audit.modifiedTime;
  else if ('lastModifiedTime' in v3Audit) out.lastModifiedTime = v3Audit.lastModifiedTime;
  return out;
}

function mapBoundary(v3b, fallbackTenantId) {
  return {
    id: v3b.id === undefined ? null : v3b.id,
    // The live v3 service returns tenantId even though boundary.yaml's Boundary
    // schema does not declare it. Prefer what v3 actually said; fall back to
    // the tenant the caller asked about.
    tenantId: v3b.tenantId === undefined ? fallbackTenantId || null : v3b.tenantId,
    code: v3b.code === undefined ? null : v3b.code,
    geometry: v3b.geometry === undefined ? null : v3b.geometry,
    auditDetails: mapAuditDetails(v3b.auditDetails),
    additionalDetails:
      v3b.additionalAttributes !== undefined
        ? v3b.additionalAttributes
        : v3b.additionalDetails !== undefined
          ? v3b.additionalDetails
          : {},
  };
}

// ---------------------------------------------------------------- v3 errors
//
// Same rewrap as idgen.js and user.js. v3 answers a failure with a BARE ARRAY
// of {code,message,description,params} and no envelope; both toV2 paths below
// used to discard it, because every `body.<key>` lookup on an array is
// undefined, and hand back a null-filled 2.x envelope carrying the upstream
// STATUS but not the REASON. server.js passes the status through, so this was
// never a correctness bug - it was an EVIDENCE bug, and evidence is the point
// of this branch.
function statusOf(v3Response) {
  if (!v3Response || typeof v3Response !== 'object') return 200;
  return typeof v3Response.status === 'number' ? v3Response.status : 200;
}

function errorEnvelope(v3Response, req2x, which, listKey) {
  const status = statusOf(v3Response);
  const json = v3Response && v3Response.json;
  const raw = v3Response && typeof v3Response.raw === 'string' ? v3Response.raw : '';

  let list = [];
  if (Array.isArray(json)) list = json;
  else if (json && Array.isArray(json.errors)) list = json.errors;
  else if (json && (json.code || json.message)) list = [json];

  const errors = list.length
    ? list.map((e) => ({
        code: e && e.code ? e.code : 'V3_ERROR',
        message: e && e.message ? e.message : String(raw).slice(0, 500),
        description: e && e.description ? e.description : null,
        params: e && e.params ? e.params : null,
      }))
    : [
        {
          code: 'V3_ERROR',
          message: String(raw).slice(0, 500) || `v3 ${which} failed with HTTP ${status}`,
          description: null,
          params: null,
        },
      ];

  gaplog.record({
    dep: DEP,
    field: 'Errors',
    action: 'faked',
    detail:
      `v3 ${which} answered HTTP ${status} with ${JSON.stringify(errors).slice(0, 400)}. DIGIT 3.0 ` +
      'returns a BARE ARRAY of {code,message,description,params}; 2.x expects ' +
      '{ResponseInfo, Errors:[...]}. Before this rewrap the array was discarded and the caller got ' +
      `an empty ${listKey} envelope carrying the status but not the reason - and on THIS service an ` +
      'empty list is not a neutral shape: it is the legitimate answer "no such boundary". The ' +
      'status is unchanged and still passed through by server.js, so nothing about pass/fail ' +
      'moves; only the reason survives now. The 2.x error fields id/parentId have no v3 source.',
  });

  const out = { ResponseInfo: responseInfo(req2x, which), Errors: errors };
  out[listKey] = [];
  return out;
}

// ------------------------------------------------- response-level key witness
//
// workflow.js closes the equivalent hole with recordTruncation(): it compares
// v3's OWN totalCount against the rows that actually arrived, so a short - or
// renamed - envelope is witnessed at the RESPONSE level instead of being
// inferred from an empty list. The v3 boundary endpoints report no totalCount,
// so the equivalent witness here is KEY PRESENCE.
//
// Both toV2 paths fall through an alias chain to `[]`, and both gap-log calls
// are guarded by `if (raw.length)` - so the one case never recorded is the
// EMPTY one, which has two very different causes:
//
//   * v3 answered `{"boundary": []}` / `{"tenantBoundary": []}` - an HONEST
//     miss (a code that does not exist, or the right code under the wrong
//     tenant). The live 2.x service answers exactly the same way and CCRS
//     turns it into INVALID_BOUNDARY_CODE itself, so this must stay silent and
//     must never be faked into a hit.
//   * v3 answered 200 carrying NONE of the spellings the chain knows - a
//     RENAMED envelope. The chain yields [], the shim returns
//     `TenantBoundary: []`, BoundaryHierarchyExpander.fetch expands the
//     employee's jurisdiction to the empty set (it swallows any failure and
//     falls back to the unexpanded code), and the inbox comes back with zero
//     complaints, HTTP 200 and no error anywhere. That is the Task 4 bug
//     verbatim - the one this whole translator exists because of.
//
// Key presence separates the two. This is a FUTURE-v3 hazard, not a live one:
// the current v3 keys are verified live and this witness stays quiet on them.
// Nothing about the reply changes - the same [] goes out either way - the case
// is only made visible.
function envelopeArrayOf(body, keys, field, which, req2x) {
  const found = keys.find((k) => Array.isArray(body && body[k]));
  if (found) return body[found];

  const seen = body && typeof body === 'object' ? Object.keys(body) : [];
  const shim = req2x && req2x.__shim;
  const path = (shim && shim.path) || '(no path on req2x.__shim)';
  const query = shim && shim.query ? JSON.stringify(shim.query) : '(no query)';
  gaplog.record({
    dep: DEP,
    field,
    action: 'unmapped',
    detail:
      `v3 ${which} answered 2xx but carried NO recognised list key for 2.x ${path} ${query}. ` +
      `Keys tried, in order: ${keys.map((k) => JSON.stringify(k)).join(', ')} - none of them held ` +
      `an array. The body carried ${seen.length ? `[${seen.slice(0, 25).join(', ')}]` : '(no keys at all)'}. ` +
      `The shim returned ${field} as an EMPTY LIST, which is indistinguishable, to every 2.x ` +
      'caller and to this shim, from v3 honestly reporting "no such boundary" - HTTP 200, ' +
      'well-formed envelope, no error. That ambiguity is the failure this entry exists to break: ' +
      'an honest empty answer arrives with the key PRESENT and empty and is recorded nowhere, ' +
      'while a renamed or missing key arrives here. Nothing was padded or invented; the list is ' +
      'still empty. Concretely, on the relationship path an empty TenantBoundary makes ' +
      'BoundaryHierarchyExpander.fetch expand the employee jurisdiction to the empty set and the ' +
      'employee complaint search return zero rows with a 200. TO BUILD: a real migration must pin ' +
      'these envelope key names by contract test rather than by an alias chain, because neither ' +
      'stack reconciles the two spellings and neither reports which one it used.',
  });
  return [];
}

function boundaryToV2(v3Response, req2x) {
  if (statusOf(v3Response) >= 400) {
    return errorEnvelope(v3Response, req2x, 'GET /boundary/v3/boundaries', 'Boundary');
  }
  const body = bodyOf(v3Response);
  // v3 says "boundary"; the plan's contract sketch said "boundaries". Accept
  // both so a later v3 build that renames the key does not silently return [].
  // A miss on BOTH is now witnessed rather than inferred - see
  // envelopeArrayOf() above; the fallback value is unchanged.
  const raw = envelopeArrayOf(
    body,
    ['boundary', 'boundaries'],
    'Boundary[]',
    'GET /boundary/v3/boundaries',
    req2x
  );

  const fallbackTenantId = tenantOf(req2x);
  const list = raw.map((b) => mapBoundary(b && typeof b === 'object' ? b : {}, fallbackTenantId));

  if (raw.length) {
    gaplog.record({
      dep: DEP,
      field: 'Boundary[]',
      action: 'defaulted',
      detail:
        `Renamed ${raw.length} v3 boundary object(s) into the 2.x shape: response key "boundary" -> ` +
        '"Boundary" (BoundaryResponse.java declares @JsonProperty("Boundary")), per-boundary ' +
        '"additionalAttributes" -> "additionalDetails", and auditDetails "modifiedBy"/"modifiedTime" ' +
        '-> "lastModifiedBy"/"lastModifiedTime". The values are carried across untouched; only the ' +
        'names differ, and nothing in either system reconciles the two spellings.',
    });
  }

  const out = { ResponseInfo: responseInfo(req2x, 'GET /boundary/v3/boundaries'), Boundary: list };

  // The canonical 2.x wire key is the capitalised one, matched exactly above.
  // pgr-services' ObjectMapper bean (PGRApp.java) sets
  // ACCEPT_CASE_INSENSITIVE_PROPERTIES and clears FAIL_ON_UNKNOWN_PROPERTIES,
  // so the lowercase alias is both harmless and independently sufficient.
  out.boundary = list;
  gaplog.record({
    dep: DEP,
    field: 'boundary',
    action: 'faked',
    detail:
      'The reply carries the boundary list TWICE: under "Boundary" (the real 2.x wire key, per ' +
      'BoundaryResponse.java @JsonProperty("Boundary"), matched byte-for-byte against the live ' +
      'boundary-service reply) and under the lowercase alias "boundary" (the Java field name, which ' +
      'is also what v3 itself uses). This is a deliberate duplicate, not a value the upstream ' +
      "reported. It is safe because pgr-services' ObjectMapper bean (PGRApp.java) disables " +
      'FAIL_ON_UNKNOWN_PROPERTIES, so the extra key is ignored by the binder. Anything else parsing ' +
      'this reply strictly would reject it.',
  });

  return out;
}

// ---------------------------------------------------------------- 2. relationship search

function relationshipToV3(req2x) {
  const q = queryOf(req2x);
  const tenantId = tenantOf(req2x);

  if (!tenantId) {
    gaplog.record({
      dep: DEP,
      field: 'tenantId',
      action: 'unmapped',
      detail:
        'No tenant could be derived from the 2.x boundary-relationship search. v3 requires ' +
        'X-Tenant-ID (400 without it); the call is forwarded as-is so it fails loudly rather than ' +
        'being defaulted to a tenant nobody asked for.',
    });
  }

  const params = new URLSearchParams();
  for (const key of ['hierarchyType', 'codes', 'includeChildren']) {
    const v = q[key];
    if (v !== undefined && v !== null && String(v) !== '') params.set(key, String(v));
  }
  // tenantId is deliberately NOT forwarded as a query param: in v3 the tenant
  // is the X-Tenant-ID header. v3 accepts and ignores a stray ?tenantId=, but
  // sending it would imply the two stacks agree on where tenancy lives.

  const qs = params.toString();
  return {
    method: 'GET',
    path: qs ? `${V3_RELATIONSHIP}?${qs}` : V3_RELATIONSHIP,
    body: undefined,
    headers: { 'X-Tenant-ID': tenantId, 'X-User-ID': userIdOf(req2x) },
  };
}

// 2.x nodes are exactly {id, code, boundaryType, children}, and a leaf still
// carries "children":[]. v3 omits `children` on a leaf and adds `auditDetails`.
function mapNode(v3node, droppedAudit) {
  const node = {
    id: v3node.id === undefined ? null : v3node.id,
    code: v3node.code === undefined ? null : v3node.code,
    boundaryType: v3node.boundaryType === undefined ? null : v3node.boundaryType,
    children: Array.isArray(v3node.children)
      ? v3node.children.map((c) => mapNode(c && typeof c === 'object' ? c : {}, droppedAudit))
      : [],
  };
  if (v3node.auditDetails !== undefined) droppedAudit.push(node.code);
  return node;
}

function relationshipToV2(v3Response, req2x) {
  if (statusOf(v3Response) >= 400) {
    return errorEnvelope(v3Response, req2x, 'GET /boundary/v3/relationship', 'TenantBoundary');
  }
  const body = bodyOf(v3Response);
  // A miss on both spellings is now witnessed - see envelopeArrayOf() above.
  // The fallback value is unchanged: still an empty list.
  const raw = envelopeArrayOf(
    body,
    ['tenantBoundary', 'TenantBoundary'],
    'TenantBoundary[]',
    'GET /boundary/v3/relationship',
    req2x
  );

  const fallbackTenantId = tenantOf(req2x);
  const droppedAudit = [];
  const list = raw.map((tb) => {
    const src = tb && typeof tb === 'object' ? tb : {};
    return {
      tenantId: src.tenantId === undefined ? fallbackTenantId || null : src.tenantId,
      hierarchyType: src.hierarchyType === undefined ? null : src.hierarchyType,
      boundary: Array.isArray(src.boundary)
        ? src.boundary.map((b) => mapNode(b && typeof b === 'object' ? b : {}, droppedAudit))
        : [],
    };
  });

  if (raw.length) {
    gaplog.record({
      dep: DEP,
      field: 'TenantBoundary[]',
      action: 'defaulted',
      detail:
        `Renamed the v3 relationship envelope key "tenantBoundary" -> "TenantBoundary" for ` +
        `${raw.length} tenant-boundary group(s). BoundaryHierarchyExpander.fetch looks up exactly ` +
        '"TenantBoundary" and, finding nothing, returns an EMPTY code set - which silently filters ' +
        'every complaint out of the employee search with a 200 and no error. The tree below the key ' +
        'is shape-identical in both stacks (code/boundaryType/children), so only the key case ' +
        'differs, and only this rename stands between a working employee search and a blank one.',
    });
    if (droppedAudit.length) {
      gaplog.record({
        dep: DEP,
        field: 'TenantBoundary[].boundary[].auditDetails',
        action: 'dropped',
        detail:
          `v3 attaches auditDetails to every node of the relationship tree (${droppedAudit.length} ` +
          'node(s) here); the live 2.x boundary-service emits none on this endpoint - its nodes are ' +
          'exactly {id, code, boundaryType, children}. Dropped so the 2.x consumer sees the shape it ' +
          'was written against. Nothing in CCRS reads it: BoundaryHierarchyExpander.collect() walks ' +
          'only `code` and `children`.',
      });
    }
    gaplog.record({
      dep: DEP,
      field: 'TenantBoundary[].boundary[].children',
      action: 'defaulted',
      detail:
        'v3 OMITS the `children` key on a leaf node; 2.x emits `children: []`. The empty array is ' +
        'reinstated so the two replies have the same shape. This one is cosmetic for CCRS - ' +
        'BoundaryHierarchyExpander.collect() null-checks children - but a consumer that iterated it ' +
        'unguarded would break on the v3 shape.',
    });
  }

  return {
    ResponseInfo: responseInfo(req2x, 'GET /boundary/v3/relationship'),
    TenantBoundary: list,
  };
}

// ---------------------------------------------------------------- dispatch

// There is NO fallback to the boundary search when the route cannot be
// determined. An earlier version defaulted to it whenever req2x carried no
// __shim (toV3) or whenever the path was not the relationship one (toV2), so a
// unit test could call the translator with a bare body. The cost of that
// affordance was that an undetermined call would have been answered with a
// {ResponseInfo, Boundary: []} envelope - no TenantBoundary key, no error - and
// BoundaryHierarchyExpander.fetch turns a missing TenantBoundary into an empty
// jurisdiction and a zero-complaint employee inbox. That is the Task 4 failure
// mode kept alive in production code for the convenience of tests. server.js
// sets __shim on every request (makeReq2x), so no live caller is affected; the
// tests construct one.
function toV3(req2x) {
  const route = routeOf(req2x);
  if (route === 'boundary') return boundaryToV3(req2x);
  if (route === 'relationship') return relationshipToV3(req2x);
  return unknownRoute(req2x);
}

function toV2(v3Response, req2x) {
  const route = routeOf(req2x);
  if (route === 'boundary') return boundaryToV2(v3Response, req2x);
  if (route === 'relationship') return relationshipToV2(v3Response, req2x);
  return unknownRoute(req2x);
}

module.exports = {
  toV3,
  toV2,
  dep: DEP,
  // exported for focused unit tests
  boundaryToV3,
  boundaryToV2,
  relationshipToV3,
  relationshipToV2,
};
