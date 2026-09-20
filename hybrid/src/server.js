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
// Prefixes are registered WITHOUT a trailing slash: router.normalize() folds
// the two spellings together, so a trailing slash would silently collide.
// Any path with no translator still answers 502 SHIM_UNMAPPED.
router.register('/egov-idgen', require('./translators/idgen'));
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
  Object.defineProperty(obj, '__shim', {
    value: {
      method: req.method,
      path: pathname,
      query: Object.fromEntries(new URL(req.url, 'http://shim.local').searchParams),
      headers: req.headers,
      rawBody: body,
    },
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

  const translator = router.resolve(pathname);
  if (!translator) {
    gaplog.record({ dep: 'router', field: pathname, action: 'unmapped' });
    send(res, 502, { Errors: [{ code: 'SHIM_UNMAPPED', message: pathname }] });
    return;
  }

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
    send(res, 502, { Errors: [{ code: 'SHIM_ERROR', message: `${pathname}: ${err.message}` }] });
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
