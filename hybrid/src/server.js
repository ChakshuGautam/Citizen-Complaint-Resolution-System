// src/server.js
//
// Stateless 2.x -> v3 translation shim. Listens on :9100, routes on the
// inbound 2.x path, hands the request to a translator, calls the v3 Kong and
// returns whatever the translator rebuilds as the 2.x envelope.
const http = require('node:http');
const router = require('./router');
const context = require('./context');
const gaplog = require('./gaplog');
const client = require('./client');

const PORT = Number(process.env.PORT || 9100);
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES || 10 * 1024 * 1024);

// --- Translator registration -------------------------------------------
// Tasks 3-6 add one line each. Registered so far:
//   /egov-idgen -> CCRS POSTs {host}egov-idgen/id/_generate (egov.idgen.path).
// Register prefixes WITHOUT a trailing slash: router.normalize() folds the two
// spellings together, and a duplicate now throws at boot rather than silently
// replacing an existing translator.
// Any path with no translator still answers 502 SHIM_UNMAPPED.
router.register('/egov-idgen', require('./translators/idgen'));
//   /boundary-service -> CCRS POSTs
//     {host}/boundary-service/boundary/_search?limit=&offset=&tenantId=&codes=
//     with a BARE RequestInfo body and the criteria in the query string.
router.register('/boundary-service', require('./translators/boundary'));
//   /egov-workflow-v2 -> CCRS POSTs THREE distinct paths under this one prefix
//     {host}/egov-workflow-v2/egov-wf/process/_transition        (transition)
//     {host}/egov-workflow-v2/egov-wf/businessservice/_search    (definition)
//     {host}/egov-workflow-v2/egov-wf/process/_search            (3 query shapes:
//        ?businessIds=<csv> | ?businessIds=<one>&history=true |
//        ?businessService=&assignee=)
//   The translator dispatches on the sub-path and throws on a fourth one.
//   Note EGOV_WORKFLOW_HOST points at egov-workflow-proxy (a pass-through
//   nginx), not at egov-workflow-v2 directly - it adds no behaviour.
router.register('/egov-workflow-v2', require('./translators/workflow'));
// -----------------------------------------------------------------------

function send(res, status, obj) {
  const payload = Buffer.from(JSON.stringify(obj));
  res.writeHead(status, {
    'content-type': 'application/json',
    'content-length': payload.length,
  });
  res.end(payload);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('request body too large'), { httpStatus: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

// req2x is the parsed 2.x request body (so translators read req2x.RequestInfo
// directly). The transport details ride along on a non-enumerable `__shim`
// property so they never leak into a payload that gets re-serialised.
function makeReq2x(body, req, pathname) {
  const obj = body && typeof body === 'object' && !Array.isArray(body) ? body : {};
  // The query string is split off the URL here and never reaches router.resolve,
  // which still matches on the path alone.
  const query = Object.fromEntries(new URL(req.url, 'http://shim.local').searchParams);
  Object.defineProperty(obj, '__shim', {
    value: {
      method: req.method,
      path: pathname,
      query,
      headers: req.headers,
      rawBody: body,
    },
    enumerable: false,
    configurable: true,
  });
  // Same object, second name. Some 2.x callers (boundary-service) put their
  // whole search criteria in the query string and post a BARE RequestInfo as
  // the body, so a translator needs the query as directly as it needs the body.
  // Non-enumerable, so it never leaks into a re-serialised payload.
  Object.defineProperty(obj, '__query', {
    value: query,
    enumerable: false,
    configurable: true,
  });
  return obj;
}

async function handle(req, res) {
  const pathname = (req.url || '/').split('?')[0];

  if (req.method === 'GET' && pathname === '/shim/health') {
    send(res, 200, { status: 'ok' });
    return;
  }

  let raw;
  try {
    raw = await readBody(req);
  } catch (err) {
    send(res, err.httpStatus || 400, { Errors: [{ code: 'SHIM_BAD_REQUEST', message: err.message }] });
    return;
  }

  let body = {};
  if (raw.length) {
    try {
      body = JSON.parse(raw);
    } catch (err) {
      send(res, 400, { Errors: [{ code: 'SHIM_BAD_JSON', message: `${pathname}: ${err.message}` }] });
      return;
    }
  }

  const match = router.resolveEntry(pathname);
  if (!match) {
    gaplog.record({ dep: 'router', field: pathname, action: 'unmapped' });
    send(res, 502, { Errors: [{ code: 'SHIM_UNMAPPED', message: pathname }] });
    return;
  }
  const translator = match.translator;

  const req2x = makeReq2x(body, req, pathname);
  try {
    const v3req = translator.toV3(req2x);
    const headers = Object.assign({}, context.toHeaders(req2x), v3req.headers || {});
    const v3res = await client.call({
      method: v3req.method,
      path: v3req.path,
      headers,
      body: v3req.body,
    });
    const out = translator.toV2(v3res, req2x);
    // Success answers 200 with the rebuilt 2.x envelope; an upstream 4xx/5xx
    // keeps its status so callers and the baseline script can see it.
    send(res, v3res.status >= 400 ? v3res.status : 200, out);
  } catch (err) {
    process.stderr.write(`shim: ${pathname}: ${err.stack || err.message}\n`);
    // A translator that throws on a real 2.x payload IS a gap - it is the
    // loudest kind there is. stderr scrolls away; the gap log is the
    // deliverable, so the failure has to land there too.
    recordFailure(match, pathname, err);
    send(res, 502, { Errors: [{ code: 'SHIM_ERROR', message: `${pathname}: ${err.message}` }] });
  }
}

// Name the dependency that threw, so the gap log says 'egov-idgen' rather than
// 'server'. A translator may declare its own `dep`; otherwise the matched route
// prefix is the best identifier we have.
function recordFailure(match, pathname, err) {
  let dep = 'server';
  try {
    if (match) {
      dep =
        (match.translator && match.translator.dep) ||
        String(match.prefix || '').replace(/^\//, '') ||
        'server';
    }
    gaplog.record({
      dep,
      field: pathname,
      action: 'unmapped',
      detail: `translator threw handling ${pathname}: ${err && err.message ? err.message : String(err)}`,
    });
  } catch (logErr) {
    // Never let gap logging stop the 502 from going out.
    process.stderr.write(`shim: gaplog failed for ${pathname}: ${logErr.message}\n`);
  }
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((err) => {
    process.stderr.write(`shim: unhandled: ${err.stack || err.message}\n`);
    if (!res.headersSent) {
      send(res, 502, { Errors: [{ code: 'SHIM_ERROR', message: err.message }] });
    } else {
      res.end();
    }
  });
});

if (require.main === module) {
  server.listen(PORT, '0.0.0.0', () => {
    process.stdout.write(
      `hybrid-shim listening on :${PORT} -> V3_BASE=${client.baseUrl()} GAP_LOG=${gaplog.logPath()}\n` +
        `routes: ${router.registered().join(', ') || '(none registered yet)'}\n`
    );
  });
}

module.exports = { server, handle };
