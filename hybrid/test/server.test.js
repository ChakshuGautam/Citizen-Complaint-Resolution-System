// test/server.test.js
//
// Exercises the request path end to end over a real socket: health, the
// unmapped 502, and - the point of this file - that a translator which THROWS
// lands in the gap log instead of only on stderr.

// Keep the gap log out of the real /opt/hybrid-shim/gaps.jsonl.
const os = require('node:os');
const path = require('node:path');
const fs = require('node:fs');
process.env.GAP_LOG = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'server-test-')), 'gaps.jsonl');

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const router = require('../src/router');
const { server } = require('../src/server');

let port;

function gaps() {
  if (!fs.existsSync(process.env.GAP_LOG)) return [];
  return fs
    .readFileSync(process.env.GAP_LOG, 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l));
}

// agent:false so no keep-alive socket keeps server.close() hanging.
function request(method, p, body) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : Buffer.from(JSON.stringify(body));
    const headers = {};
    if (payload) {
      headers['content-type'] = 'application/json';
      headers['content-length'] = payload.length;
    }
    const req = http.request(
      { host: '127.0.0.1', port, path: p, method, headers, agent: false },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const raw = Buffer.concat(chunks).toString('utf8');
          let json = null;
          try {
            json = JSON.parse(raw);
          } catch {
            json = null;
          }
          resolve({ status: res.statusCode, json, raw });
        });
      }
    );
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

before(async () => {
  router.register('/boom', {
    toV3() {
      throw new Error('boom: cannot map this payload');
    },
    toV2() {
      return {};
    },
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  port = server.address().port;
});

after(async () => {
  await new Promise((r) => server.close(r));
});

test('health answers ok', async () => {
  const res = await request('GET', '/shim/health');
  assert.strictEqual(res.status, 200);
  assert.deepStrictEqual(res.json, { status: 'ok' });
});

test('an unmapped path answers 502 and records the gap', async () => {
  const before = gaps().length;
  const res = await request('POST', '/egov-nosuch/v1/_search', { RequestInfo: {} });
  assert.strictEqual(res.status, 502);
  assert.strictEqual(res.json.Errors[0].code, 'SHIM_UNMAPPED');
  assert.strictEqual(res.json.Errors[0].message, '/egov-nosuch/v1/_search');

  const g = gaps()
    .slice(before)
    .find((x) => x.field === '/egov-nosuch/v1/_search');
  assert.ok(g, 'expected an unmapped gap entry');
  assert.strictEqual(g.dep, 'router');
  assert.strictEqual(g.action, 'unmapped');
});

test('a throwing translator answers 502 SHIM_ERROR AND records a gap naming the dependency', async () => {
  const before = gaps().length;
  const res = await request('POST', '/boom/id/_generate', {
    RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'pg' } },
  });

  // The 502 behaviour is unchanged...
  assert.strictEqual(res.status, 502);
  assert.strictEqual(res.json.Errors[0].code, 'SHIM_ERROR');
  assert.match(res.json.Errors[0].message, /boom: cannot map this payload/);

  // ...and the failure is now in the deliverable, not just on stderr.
  const g = gaps()
    .slice(before)
    .find((x) => x.field === '/boom/id/_generate');
  assert.ok(g, 'a translator that throws is a gap and must be logged');
  assert.strictEqual(g.action, 'unmapped');
  assert.strictEqual(g.dep, 'boom', 'the dep names the route that failed, not "server"');
  assert.match(g.detail, /boom: cannot map this payload/);
});
