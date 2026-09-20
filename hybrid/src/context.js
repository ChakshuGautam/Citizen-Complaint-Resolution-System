// src/context.js
//
// DIGIT 3.0 carries the tenant and the acting user as HEADERS, not inside a
// body envelope: every v3 endpoint probed on this box answers 400 without
// X-Tenant-ID. The 2.x RequestInfo is the only place the shim can derive them
// from, so when that lookup yields nothing this function emits an EMPTY
// header - and an empty header is a default like any other. It is not
// harmless either: v3 answers 400 "Missing X-Tenant-Id header", which the shim
// surfaces as a 502 the caller reads as a shim fault rather than as a missing
// field in its own request. It is also not always visible, because a
// translator that supplies its own headers (boundary.js does, and must -
// see its header comment) silently overwrites the empty one before the call.
//
// Either way the shim defaulted a value it could not derive, and this branch
// ships no silent defaults. Behaviour is unchanged - still '' - but the miss
// is now on the record, named by the caller that asked.
const gaplog = require('./gaplog');

const DEP = 'shim-context';

function shapeOf(body) {
  if (!body || typeof body !== 'object') return `(body is ${body === null ? 'null' : typeof body})`;
  const keys = Object.keys(body);
  return keys.length ? `top-level keys [${keys.slice(0, 20).join(', ')}]` : '(body has no keys)';
}

// which - free text naming the caller (server.js passes the inbound 2.x path),
//         so a gap entry says WHERE the header could not be derived.
function toHeaders(body, which) {
  const ri = (body && body.RequestInfo) || {};
  const ui = ri.userInfo || {};
  const where = which ? String(which) : '(caller not named)';

  const tenantId = ui.tenantId || ri.tenantId || '';
  if (!tenantId) {
    gaplog.record({
      dep: DEP,
      field: 'X-Tenant-ID',
      action: 'defaulted',
      detail:
        `Could not derive a tenant for ${where}. Keys tried, in order: ` +
        'RequestInfo.userInfo.tenantId, RequestInfo.tenantId - both absent or empty; the payload ' +
        `carried ${shapeOf(body)}. Sent X-Tenant-ID: "" (empty). The tenant is a REQUIRED header ` +
        'dimension on every v3 endpoint probed here, so unless the translator supplies its own ' +
        '(boundary.js does, because the 2.x boundary caller posts a BARE RequestInfo with no ' +
        'RequestInfo wrapper at all and keeps its criteria in the query string) v3 answers 400 ' +
        '"Missing X-Tenant-Id header" and the shim returns 502. TO BUILD: v3 has no notion of a ' +
        'request-scoped tenant outside the header, so a real migration must carry the tenant ' +
        'explicitly on every call rather than inferring it from the auth envelope.',
    });
  }

  const userId = ui.uuid || '';
  if (!userId) {
    gaplog.record({
      dep: DEP,
      field: 'X-User-ID',
      action: 'defaulted',
      detail:
        `Could not derive an acting user for ${where}. Key tried: RequestInfo.userInfo.uuid - ` +
        `absent or empty; the payload carried ${shapeOf(body)}. Sent X-User-ID: "" (empty). v3 ` +
        'requires it on the write paths (template create, transition) where it lands in ' +
        'auditDetail.createdBy/modifiedBy; on the read paths it is accepted and ignored. An empty ' +
        'value therefore does not fail a read, but it does mean any v3 row written through this ' +
        'call is attributed to nobody.',
    });
  }

  return {
    'X-Tenant-ID': tenantId,
    'X-User-ID': userId,
  };
}
module.exports = { toHeaders };
