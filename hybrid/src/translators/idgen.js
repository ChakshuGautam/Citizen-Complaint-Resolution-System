// src/translators/idgen.js
//
// CCRS (DIGIT 2.12) egov-idgen  ->  DIGIT 3.0 idgen.
//
// =====================================================================
// OBSERVED CONTRACTS (probed live on this box 2026-09-20, not guessed)
// =====================================================================
//
// --- 2.x, what CCRS sends and expects -------------------------------
// pgr-services EnrichmentService.enrichCreateRequest -> IdGenRepository.getId
// builds `count` IDENTICAL IdRequest entries, so a batch is homogeneous by
// construction, and for PGR `count` is always 1.
//
//   POST {EGOV_IDGEN_HOST}egov-idgen/id/_generate
//   {"RequestInfo":{...,"userInfo":{"uuid":"...","tenantId":"pg"}},
//    "idRequests":[{"idName":"pgr.servicerequestid","tenantId":"pg",
//                   "format":"PB-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]"}]}
//
// Live 2.x response (curl against http://localhost:18000, the 2.x Kong):
//   {"responseInfo":{"apiId":"Rainmaker","ver":".01","ts":0,
//                    "resMsgId":"uief87324","msgId":"probe",
//                    "status":"SUCCESSFUL"},
//    "idResponses":[{"id":"PG-PGR-2026-09-20-005216"}]}
//
//   NOTE the lowercase `responseInfo`. IdGenerationResponse.java declares
//   `private ResponseInfo responseInfo;` with NO @JsonProperty, so Jackson
//   binds the lowercase key. `idResponses[].id` is the only field CCRS reads.
//
//   NOTE also: 2.x idgen IGNORED the `format` CCRS sent ("PB-PGR-...") and
//   used the MDMS common-masters.IdFormat entry for tenant `pg`
//   ("PG-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]"). MDMS wins over the request.
//
// --- v3, probed against http://d3-kong:8000 -------------------------
//   POST /idgen/v3/generate
//     headers: X-Tenant-ID REQUIRED (400 without it).
//              X-User-ID is NOT required for generate (it IS for template
//              create). We send it anyway; v3 ignores it here.
//     body:    {"templateCode":"<code>"}          (optional "version")
//              a `count` field on this path is ACCEPTED AND IGNORED.
//     200:     {"templateCode":"zz.probe3","version":"v1",
//               "id":"ZZ-2026-09-20-000001"}      <- singular `id`, a string
//     404:     [{"code":"NOT_FOUND","message":"template not found",
//                "description":"","params":[]}]   <- a bare ARRAY, no envelope
//     400:     [{"code":"VALIDATION_ERROR","message":"field 'templateCode'
//                failed 'required' validation",...}]
//
//   POST /idgen/v3/generate/bulk
//     body:    {"templateCode":"<code>","count":2}
//     200:     {"templateCode":"...","version":"v1","count":2,
//               "ids":["ZZ-2026-09-20-000004","ZZ-2026-09-20-000005"]}
//
//   POST /idgen/v3/template (used once, out of band, to register the
//     template; X-User-ID is required here and lands in auditDetail.createdBy)
//     body:    {"templateCode":"...","config":{"template":"...",
//               "sequence":{"scope":"GLOBAL","start":1,
//                           "padding":{"length":6,"char":"0"}}}}
//     201:     the stored template. `config.sequence.padding` is null unless
//              given as an OBJECT - paddingLength/paddingChar are silently
//              dropped by v3's binder.
//
// --- what does not cross the boundary -------------------------------
//   * `format` has no home in v3. v3 templates are pre-registered by code;
//     the caller cannot supply a pattern per request. Recorded as `dropped`.
//   * v3 token syntax differs: 2.x `[cy:yyyy-MM-dd]` -> v3 `{DATE:yyyy-MM-dd}`,
//     2.x `[SEQ_EG_PGR_ID]` (a NAMED, shared Postgres sequence) -> v3 `{SEQ}`
//     (a per-template sequence). There is no way to point a v3 template at an
//     existing named sequence, so the v3 counter is independent of
//     `seq_eg_pgr_id`. Recorded at template-registration time.
//   * A 2.x batch with MORE THAN ONE distinct idName cannot be served by one
//     v3 call (v3 generate takes exactly one templateCode). CCRS never emits
//     one, but rather than silently returning the wrong ids we record
//     `unmapped` and throw, which the shim surfaces as 502 SHIM_ERROR.
// =====================================================================

const gaplog = require('../gaplog');
const context = require('../context');

const DEP = 'egov-idgen';

function idRequestsOf(req2x) {
  const reqs = (req2x && req2x.idRequests) || [];
  return Array.isArray(reqs) ? reqs : [];
}

// 2.x -> v3 request.
function toV3(req2x) {
  const reqs = idRequestsOf(req2x);

  if (reqs.length === 0) {
    gaplog.record({
      dep: DEP,
      field: 'idRequests',
      action: 'unmapped',
      detail: 'CCRS sent /egov-idgen/id/_generate with an empty or missing idRequests[]. ' +
        'v3 /idgen/v3/generate has no equivalent of "generate nothing"; refusing rather than inventing an id.',
    });
    throw new Error('idgen: idRequests[] is empty');
  }

  const names = [...new Set(reqs.map((r) => (r && r.idName) || ''))];
  if (names.length > 1) {
    gaplog.record({
      dep: DEP,
      field: 'idRequests[].idName',
      action: 'unmapped',
      detail: `A single 2.x _generate batch carried ${names.length} distinct idNames (${names.join(', ')}). ` +
        'v3 /idgen/v3/generate and /idgen/v3/generate/bulk each take exactly one templateCode, ' +
        'so one 2.x call cannot become one v3 call. Refusing rather than returning ids minted from the wrong template.',
    });
    throw new Error(`idgen: heterogeneous idNames in one batch: ${names.join(', ')}`);
  }

  const templateCode = names[0];
  if (!templateCode) {
    gaplog.record({
      dep: DEP,
      field: 'idRequests[].idName',
      action: 'unmapped',
      detail: 'idName was empty. v3 requires templateCode (binding: required,min=2,max=64).',
    });
    throw new Error('idgen: idRequests[].idName is empty');
  }

  // `format` is a per-request pattern in 2.x. v3 has no home for it: the
  // pattern lives in the pre-registered template. Never drop it silently.
  const formats = [...new Set(reqs.map((r) => (r && r.format) || '').filter(Boolean))];
  if (formats.length) {
    gaplog.record({
      dep: DEP,
      field: 'format',
      action: 'dropped',
      detail: `v3 templates are pre-registered by templateCode; /idgen/v3/generate accepts no per-request ` +
        `pattern. CCRS sent format=${JSON.stringify(formats.join(' | '))} for idName=${JSON.stringify(templateCode)} ` +
        `and it was discarded. (2.x also discarded it - the live egov-idgen used the MDMS ` +
        `common-masters.IdFormat entry instead - so the ID SHAPE is unchanged, but the source of truth moved ` +
        `from MDMS to the v3 template store.)`,
    });
  }

  // The authoritative tenant for idgen is idRequests[].tenantId - that is what
  // 2.x used to pick the MDMS format and the sequence. context.toHeaders()
  // derives a tenant from RequestInfo, which can legitimately differ (a citizen
  // registered at `pg` filing against `pg.citya`). Prefer the idRequest, and say
  // so when the two disagree.
  const tenants = [...new Set(reqs.map((r) => (r && r.tenantId) || '').filter(Boolean))];
  const headers = {};
  if (tenants.length) {
    const fromRequestInfo = context.toHeaders(req2x)['X-Tenant-ID'];
    if (tenants.length > 1) {
      gaplog.record({
        dep: DEP,
        field: 'idRequests[].tenantId',
        action: 'dropped',
        detail: `A single batch carried ${tenants.length} distinct tenantIds (${tenants.join(', ')}). ` +
          `v3 carries the tenant in one X-Tenant-ID header per call, so only ${JSON.stringify(tenants[0])} was used.`,
      });
    }
    if (fromRequestInfo !== tenants[0]) {
      gaplog.record({
        dep: DEP,
        field: 'X-Tenant-ID',
        action: 'defaulted',
        detail: `idRequests[0].tenantId=${JSON.stringify(tenants[0])} but RequestInfo resolved to ` +
          `${JSON.stringify(fromRequestInfo)}. Used the idRequest tenant, which is the one 2.x idgen keyed on.`,
      });
    }
    headers['X-Tenant-ID'] = tenants[0];
  }

  const count = reqs.length;
  return count === 1
    ? { method: 'POST', path: '/idgen/v3/generate', body: { templateCode }, headers }
    : { method: 'POST', path: '/idgen/v3/generate/bulk', body: { templateCode, count }, headers };
}

// The 2.x envelope CCRS deserialises. `responseInfo` is lowercase on purpose
// (IdGenerationResponse.java has no @JsonProperty) and is entirely synthesised
// here - v3 returns no envelope at all - so it is recorded as `faked`.
function responseInfo(req2x, status) {
  const ri = (req2x && req2x.RequestInfo) || {};
  gaplog.record({
    dep: DEP,
    field: 'responseInfo',
    action: 'faked',
    detail: 'v3 /idgen/v3/generate returns a bare {templateCode, version, id} with no ResponseInfo. ' +
      'CCRS IdGenerationResponse declares `responseInfo`, so the shim synthesises one: apiId/ver/ts/msgId ' +
      'echoed from the inbound RequestInfo, resMsgId hardcoded to "uief87324" (the constant the live 2.x ' +
      'egov-idgen returns) and status computed from the v3 HTTP status. Nothing downstream reads it today.',
  });
  return {
    apiId: ri.apiId || null,
    ver: ri.ver || null,
    ts: ri.ts === undefined ? 0 : ri.ts,
    resMsgId: 'uief87324',
    msgId: ri.msgId || null,
    status: status >= 400 ? 'FAILED' : 'SUCCESSFUL',
  };
}

// v3 -> 2.x response.
// v3Response is the src/client.js result: {status, json, raw, headers}.
function toV2(v3Response, req2x) {
  const res = v3Response || {};
  const status = res.status === undefined ? 200 : res.status;
  const json = res.json;

  const out = { responseInfo: responseInfo(req2x, status) };

  if (status >= 400) {
    // Deliberately NOT masked as an empty success: server.js passes the v3
    // status through, CCRS's RestTemplate raises, and the lifecycle turns red.
    // v3 errors are a bare array, so they are carried under the 2.x `Errors`
    // key CCRS's tracer understands.
    out.idResponses = [];
    out.Errors = Array.isArray(json)
      ? json
      : [{ code: 'IDGEN_V3_ERROR', message: (json && json.message) || res.raw || `v3 idgen returned ${status}` }];
    return out;
  }

  let ids = [];
  if (json && Array.isArray(json.ids)) ids = json.ids;
  else if (json && typeof json.id === 'string') ids = [json.id];

  if (!ids.length) {
    gaplog.record({
      dep: DEP,
      field: 'idResponses',
      action: 'unmapped',
      detail: `v3 answered ${status} but carried neither an "id" string nor an "ids" array ` +
        `(body: ${JSON.stringify(json === null ? res.raw : json).slice(0, 300)}). ` +
        'Returning an empty idResponses[]; CCRS raises "IDGEN ERROR: No ids returned from idgen Service".',
    });
  }

  out.idResponses = ids.map((id) => ({ id }));
  return out;
}

module.exports = { toV3, toV2 };
