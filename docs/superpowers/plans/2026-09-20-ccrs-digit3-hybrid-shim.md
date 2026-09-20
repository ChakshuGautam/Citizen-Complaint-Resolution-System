# CCRS × DIGIT 3.0 Hybrid Shim — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove which CCRS (DIGIT 2.12) dependencies can be served by DIGIT 3.0 services today, by re-pointing them one at a time through a stateless translation shim, and record every field-level gap.

**Architecture:** One Node process (`hybrid-shim`) listens on :9100, routes on the inbound 2.x path, translates the request to v3 REST with `X-Tenant-ID`/`X-User-ID` headers, calls the v3 Kong on :8000, and rebuilds the 2.x response envelope. CCRS changes only `EGOV_*_HOST` environment variables. Translators are pure functions, tested in isolation.

**Tech Stack:** Node 20 (`node:http`, `node:test` — no npm dependencies), Docker Compose, bash + curl for lifecycle tests.

**Spec:** `docs/superpowers/specs/2026-09-20-ccrs-digit3-hybrid-design.md`

## Global Constraints

- Work happens on **egov-subhashini (37.27.254.159)**, reached as `ssh egov-subhashini` from egov-server (95.217.180.65). **egov-bomet (62.238.3.196) is never touched.**
- CCRS 2.12 runs as compose project `digit` from `/opt/digit`. State-level tenant is **`mz`**.
- DIGIT 3.0 runs as compose project `digit3` from `/root/digit3/digit3/deploy/local`, Kong on **:8000**, container names prefixed `d3-`.
- The shim runs as compose project `hybrid` from `/opt/hybrid-shim`, port **9100**, attached to both stacks' networks.
- **No npm dependencies.** Node standard library only, so the image needs no registry access.
- Every invented default, dropped field or faked response key MUST be written to the gap log at `/opt/hybrid-shim/gaps.jsonl`. Silent defaults are a bug.
- Rollback for any task: restore the `EGOV_*_HOST` value in `/opt/digit/docker-compose.egov-digit.yaml` and `docker compose -p digit up -d pgr-services`.
- v3 services currently require **no auth** (Kong has zero plugins). Do not add token handling.
- Localization is **out of scope**: no localization service is deployed in the v3 stack.

---

### Task 1: 2.x baseline lifecycle

**Files:**
- Create: `/opt/hybrid-shim/baseline/lifecycle.sh`
- Create: `/opt/hybrid-shim/baseline/README.md`
- Output: `/opt/hybrid-shim/baseline/baseline-YYYYMMDD-HHMM.json`

**Interfaces:**
- Consumes: nothing.
- Produces: `lifecycle.sh` — exits 0 when create → assign → resolve all succeed; writes a JSON record `{step, status, http_code, body}` per call to the output file named by `$OUT`. Later tasks re-run this exact script and diff against the baseline.

- [ ] **Step 1: Find the citizen auth call that works today**

```bash
ssh egov-subhashini 'curl -s -o /tmp/t -w "%{http_code}\n" \
  -H "Authorization: Basic ZWdvdi11c2VyLWNsaWVudDo=" \
  -d "grant_type=password&username=9999999999&password=123456&tenantId=mz&userType=CITIZEN&scope=read" \
  http://localhost:11234/user/oauth/token; head -c 300 /tmp/t'
```

Expected: `200` and a body containing `access_token`. If it is 400/401, list existing citizen users first (`docker exec digit-postgres psql -U postgres -d digit -c "select username,type,tenantid from eg_user where type='CITIZEN' limit 5"`) and use one of those, or create one via `/user/users/_createnovalidate`. Record the working credentials in `baseline/README.md`.

- [ ] **Step 2: Write `lifecycle.sh`**

The script must: fetch a citizen token; POST a complaint to `/pgr-services/v2/request/_create`; poll `/v2/request/_search` until the complaint appears; fetch an employee token; POST a workflow action `ASSIGN`; POST `RESOLVE`; then search once more and assert the final `applicationStatus`. Every call appends a record to `$OUT` via a `record()` helper:

```bash
record() {  # record <step> <http_code> <body_file>
  python3 - "$1" "$2" "$3" "$OUT" <<'PY'
import json,sys
step,code,body_file,out=sys.argv[1:5]
try: body=json.load(open(body_file))
except Exception: body=open(body_file).read()[:2000]
rec={"step":step,"http_code":int(code),"body":body}
with open(out,"a") as f: f.write(json.dumps(rec,sort_keys=True)+"\n")
PY
}
```

Use `set -uo pipefail`, and make every step print `PASS <step>` or `FAIL <step> <http_code>`.

- [ ] **Step 3: Run it against untouched CCRS**

Run: `ssh egov-subhashini 'OUT=/opt/hybrid-shim/baseline/baseline-$(date +%Y%m%d-%H%M).json bash /opt/hybrid-shim/baseline/lifecycle.sh'`
Expected: every step prints PASS and exit code 0.

**If it fails, STOP.** Fix CCRS first and report what was broken. Everything downstream is measured against this baseline, so a red baseline makes the rest meaningless.

- [ ] **Step 4: Record the complaint-type and locality codes that worked**

Append to `baseline/README.md` the exact `serviceCode`, `locality` and tenant used, so later runs are comparable.

- [ ] **Step 5: Commit**

```bash
cd /root/code/Citizen-Complaint-Resolution-System && \
  mkdir -p hybrid/baseline && cp /opt/hybrid-shim/baseline/lifecycle.sh /opt/hybrid-shim/baseline/README.md hybrid/baseline/ && \
  git add hybrid/baseline && git commit -m "test: 2.x baseline lifecycle script for hybrid work"
```

---

### Task 2: Shim skeleton (router, context, gaplog, client)

**Files:**
- Create: `/opt/hybrid-shim/src/server.js`, `src/router.js`, `src/context.js`, `src/gaplog.js`, `src/client.js`
- Create: `/opt/hybrid-shim/test/context.test.js`, `test/router.test.js`
- Create: `/opt/hybrid-shim/Dockerfile`, `/opt/hybrid-shim/docker-compose.yml`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `context.toHeaders(body) -> {"X-Tenant-ID": string, "X-User-ID": string}` — reads `body.RequestInfo.userInfo.uuid` and `body.RequestInfo.userInfo.tenantId ?? body.RequestInfo.tenantId`.
  - `gaplog.record({dep, field, action, detail})` where `action` is one of `"dropped" | "defaulted" | "faked" | "unmapped"`.
  - `router.register(pathPrefix, translator)` and `router.resolve(path) -> translator | null`.
  - `client.call({method, path, headers, body}) -> {status, json}` against `process.env.V3_BASE` (default `http://d3-kong:8000`).
  - A translator is `{ toV3(req2x) -> {method, path, body}, toV2(v3Response, req2x) -> object }`.

- [ ] **Step 1: Write the failing test for `context.toHeaders`**

```javascript
// test/context.test.js
const test = require('node:test');
const assert = require('node:assert');
const context = require('../src/context');

test('maps RequestInfo userInfo to v3 headers', () => {
  const body = { RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'mz' } } };
  assert.deepStrictEqual(context.toHeaders(body), {
    'X-Tenant-ID': 'mz',
    'X-User-ID': 'u-1',
  });
});

test('falls back to RequestInfo.tenantId', () => {
  const body = { RequestInfo: { tenantId: 'mz', userInfo: { uuid: 'u-2' } } };
  assert.strictEqual(context.toHeaders(body)['X-Tenant-ID'], 'mz');
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/'`
Expected: FAIL, `Cannot find module '../src/context'`.

- [ ] **Step 3: Implement `context.js`**

```javascript
// src/context.js
function toHeaders(body) {
  const ri = (body && body.RequestInfo) || {};
  const ui = ri.userInfo || {};
  return {
    'X-Tenant-ID': ui.tenantId || ri.tenantId || '',
    'X-User-ID': ui.uuid || '',
  };
}
module.exports = { toHeaders };
```

- [ ] **Step 4: Run the test again**

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/'`
Expected: PASS.

- [ ] **Step 5: Write `gaplog.js`, `client.js`, `router.js`, `server.js`**

`gaplog.record` appends one JSON line to `process.env.GAP_LOG || '/opt/hybrid-shim/gaps.jsonl'`, each with an ISO timestamp. `router.resolve` does longest-prefix matching over registered paths. `server.js` reads the whole request body, resolves a translator, and on `null` responds **502** with `{"Errors":[{"code":"SHIM_UNMAPPED","message":path}]}` after calling `gaplog.record({dep:'router', field:path, action:'unmapped'})`. Add `GET /shim/health` returning `{"status":"ok"}`.

- [ ] **Step 6: Test the unmapped path**

```javascript
// test/router.test.js
const test = require('node:test');
const assert = require('node:assert');
const router = require('../src/router');

test('unknown path resolves to null', () => {
  assert.strictEqual(router.resolve('/nope/_search'), null);
});

test('registered prefix wins', () => {
  const t = { toV3: () => ({}), toV2: () => ({}) };
  router.register('/egov-idgen', t);
  assert.strictEqual(router.resolve('/egov-idgen/id/_generate'), t);
});
```

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/'` → PASS.

- [ ] **Step 7: Containerise and start**

```dockerfile
FROM node:20-alpine
WORKDIR /app
COPY src ./src
COPY test ./test
ENV GAP_LOG=/gaps/gaps.jsonl
EXPOSE 9100
CMD ["node", "src/server.js"]
```

`docker-compose.yml` defines service `hybrid-shim` (container_name `hybrid-shim`), port `9100:9100`, a bind mount for `/gaps`, and joins the external networks of both stacks. Find their names with `docker network ls | grep -E 'digit|digit3'` and use `networks: {<ccrs-net>: {external: true}, <digit3-net>: {external: true}}`.

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && docker compose -p hybrid up -d --build && curl -s http://localhost:9100/shim/health'`
Expected: `{"status":"ok"}`.

- [ ] **Step 8: Verify the shim can reach v3 Kong**

Run: `ssh egov-subhashini 'docker exec hybrid-shim wget -qO- http://d3-kong:8000/idgen/v3/template'`
Expected: `[]`. If DNS fails, the network wiring in Step 7 is wrong — fix before continuing.

- [ ] **Step 9: Commit**

```bash
cd /root/code/Citizen-Complaint-Resolution-System && cp -r /opt/hybrid-shim/{src,test,Dockerfile,docker-compose.yml} hybrid/ && \
  git add hybrid && git commit -m "feat(hybrid): shim skeleton with router, context, gaplog and client"
```

---

### Task 3: idgen translator

**Files:**
- Create: `/opt/hybrid-shim/src/translators/idgen.js`, `/opt/hybrid-shim/test/idgen.test.js`
- Modify: `/opt/hybrid-shim/src/server.js` (register the translator)
- Modify: `/opt/digit/docker-compose.egov-digit.yaml` (`EGOV_IDGEN_HOST` in the pgr-services block)

**Interfaces:**
- Consumes: `context.toHeaders`, `gaplog.record`, `client.call`, `router.register` from Task 2.
- Produces: `translators/idgen.js` exporting `{toV3, toV2}` registered at prefix `/egov-idgen`.

CCRS calls `POST {host}egov-idgen/id/_generate` with body `{RequestInfo, idRequests:[{idName, tenantId, format}]}` and expects `{ResponseInfo, idResponses:[{id}]}` (`repository/IdGenRepository.java:55`). v3 is `POST /idgen/v3/generate`.

- [ ] **Step 1: Capture the real v3 contract**

```bash
ssh egov-subhashini 'python3 -c "
import yaml,json
d=yaml.safe_load(open(\"/root/digitnxt-new/digitnxt/digit-specs/v3.0.0/idgen.yaml\"))
print(json.dumps(d[\"paths\"][\"/generate\"][\"post\"],indent=1)[:1500])"'
```

Write the observed request and response field names into a comment at the top of `idgen.js`. Do not guess them.

- [ ] **Step 2: Write the failing translator test**

```javascript
// test/idgen.test.js
const test = require('node:test');
const assert = require('node:assert');
const idgen = require('../src/translators/idgen');

test('maps idRequests to the v3 generate call', () => {
  const req = { RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'mz' } },
                idRequests: [{ idName: 'pgr.servicerequestid', tenantId: 'mz', format: 'PGR-[cy:yyyy-MM-dd]-[SEQ_EGOV_COMMON]' }] };
  const out = idgen.toV3(req);
  assert.strictEqual(out.method, 'POST');
  assert.strictEqual(out.path, '/idgen/v3/generate');
});

test('wraps v3 ids back into idResponses', () => {
  const v2 = idgen.toV2({ ids: ['PGR-2026-09-20-000123'] }, {});
  assert.deepStrictEqual(v2.idResponses, [{ id: 'PGR-2026-09-20-000123' }]);
});
```

Adjust the v3 field names to whatever Step 1 showed.

- [ ] **Step 3: Run it and watch it fail**

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/idgen.test.js'`
Expected: FAIL, module not found.

- [ ] **Step 4: Implement the translator**

Map each `idRequests[]` entry to the v3 body. When v3 has no home for `format`, call `gaplog.record({dep:'idgen', field:'format', action:'dropped', detail:'v3 templates are pre-registered'})` rather than dropping it silently. `toV2` must include a `ResponseInfo` stub, because CCRS deserialises it.

- [ ] **Step 5: Run the tests**

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/'` → PASS.

- [ ] **Step 6: Register the ID template in v3**

CCRS's format must exist in v3 before generation works:

```bash
ssh egov-subhashini '/usr/local/bin/digit create-idgen-template --help'
```

Use the CLI (or `POST /idgen/v3/template` via curl with `X-Tenant-ID: mz`) to register the `pgr.servicerequestid` template. Record the exact command in `hybrid/README.md`.

- [ ] **Step 7: Switch CCRS over**

In `/opt/digit/docker-compose.egov-digit.yaml`, in the pgr-services block only, set `EGOV_IDGEN_HOST: http://hybrid-shim:9100/`. Then:

```bash
ssh egov-subhashini 'cd /opt/digit && docker compose -p digit up -d pgr-services && sleep 20 && docker logs --tail 20 digit-pgr-services-1'
```

- [ ] **Step 8: Re-run the lifecycle and diff**

Run: `ssh egov-subhashini 'OUT=/opt/hybrid-shim/runs/idgen-$(date +%H%M).json bash /opt/hybrid-shim/baseline/lifecycle.sh'`
Expected: all steps PASS, and the created complaint has a service request ID. Diff the run against the baseline, ignoring IDs and timestamps.

If it fails: record the failing call in the gap log, revert `EGOV_IDGEN_HOST`, restart pgr-services, and continue to Task 4 — a failure here is a finding, not a blocker.

- [ ] **Step 9: Commit**

```bash
cd /root/code/Citizen-Complaint-Resolution-System && cp -r /opt/hybrid-shim/src /opt/hybrid-shim/test hybrid/ && \
  git add hybrid && git commit -m "feat(hybrid): idgen translator, CCRS idgen served by DIGIT 3"
```

---

### Task 4: boundary translator

**Files:**
- Create: `/opt/hybrid-shim/src/translators/boundary.js`, `/opt/hybrid-shim/test/boundary.test.js`
- Modify: `/opt/hybrid-shim/src/server.js`, `/opt/digit/docker-compose.egov-digit.yaml` (`EGOV_BOUNDARY_HOST`)

**Interfaces:**
- Consumes: Task 2 modules.
- Produces: `translators/boundary.js` exporting `{toV3, toV2}` at prefix `/boundary-service`.

CCRS calls `POST {host}boundary-service/boundary/_search?limit=10&offset=0&tenantId=..&codes=..` with a **bare `RequestInfo` body, not wrapped**, and expects a `boundary[]` array (`validator/ServiceRequestValidator.java:315-323`). v3 is `GET /boundary/v3/boundaries`.

- [ ] **Step 1: Capture the v3 contract**

```bash
ssh egov-subhashini 'curl -s -H "X-Tenant-ID: mz" "http://localhost:8000/boundary/v3/boundaries?codes=X" | head -c 400'
```

- [ ] **Step 2: Write the failing test**

```javascript
// test/boundary.test.js
const test = require('node:test');
const assert = require('node:assert');
const boundary = require('../src/translators/boundary');

test('moves query-string codes onto the v3 GET', () => {
  const out = boundary.toV3({ __query: { tenantId: 'mz', codes: 'A,B', limit: '10', offset: '0' },
                              RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'mz' } } });
  assert.strictEqual(out.method, 'GET');
  assert.ok(out.path.startsWith('/boundary/v3/boundaries'));
  assert.ok(out.path.includes('codes=A%2CB') || out.path.includes('codes=A,B'));
});

test('returns a boundary array under the 2.x key', () => {
  const v2 = boundary.toV2({ boundaries: [{ code: 'A', name: 'A' }] }, {});
  assert.strictEqual(v2.boundary.length, 1);
});
```

- [ ] **Step 3: Run it and watch it fail**

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/boundary.test.js'` → FAIL.

- [ ] **Step 4: Pass the query string through the router**

`server.js` must split `?` off the URL and hand the parsed query to the translator as `req.__query`. Update `router.resolve` callers accordingly; keep `resolve` matching on the path only.

- [ ] **Step 5: Implement the translator, then run the tests**

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/'` → PASS.

- [ ] **Step 6: Seed matching boundaries in v3**

CCRS validates the complaint's locality against boundary search. The v3 boundary store is empty, so the baseline's locality code must exist there:

```bash
ssh egov-subhashini '/usr/local/bin/digit create-boundaries --help'
```

Create the hierarchy and the single locality code the baseline uses. Record the command in `hybrid/README.md`.

- [ ] **Step 7: Switch, re-run the lifecycle, diff**

Set `EGOV_BOUNDARY_HOST: http://hybrid-shim:9100/` in the pgr-services block, `docker compose -p digit up -d pgr-services`, then re-run `lifecycle.sh` with `OUT=/opt/hybrid-shim/runs/boundary-$(date +%H%M).json`. Same failure rule as Task 3.

- [ ] **Step 8: Commit**

```bash
cd /root/code/Citizen-Complaint-Resolution-System && cp -r /opt/hybrid-shim/src /opt/hybrid-shim/test hybrid/ && \
  git add hybrid && git commit -m "feat(hybrid): boundary translator"
```

---

### Task 5: workflow translator

**Files:**
- Create: `/opt/hybrid-shim/src/translators/workflow.js`, `/opt/hybrid-shim/test/workflow.test.js`
- Create: `/opt/hybrid-shim/v3-config/pgr-process-definition.yaml`
- Modify: `/opt/hybrid-shim/src/server.js`, `/opt/digit/docker-compose.egov-digit.yaml` (`EGOV_WORKFLOW_HOST`)

**Interfaces:**
- Consumes: Task 2 modules.
- Produces: `translators/workflow.js` exporting `{toV3, toV2}` at prefix `/egov-workflow-v2`.

This is the real test. CCRS makes four distinct calls (`service/WorkflowService.java:94, 244, 253, 264`):

| 2.x call | Body | v3 equivalent |
|---|---|---|
| `POST /egov-wf/process/_transition` | `ProcessInstanceRequest` (wraps RequestInfo) | `POST /workflow/v3/transition` |
| `POST …/businessservice/_search?tenantId=&businessServices=` | `RequestInfoWrapper` | `GET /workflow/v3/process/definition` |
| `POST …/process/_search?tenantId=&businessIds=` | `RequestInfoWrapper` | `GET /workflow/v3/transition` |
| `POST …/process/_search?tenantId=&businessService=&assignee=` | `RequestInfoWrapper` | `GET /workflow/v3/transition` (inbox) |

- [ ] **Step 1: Export CCRS's live PGR BusinessService definition**

```bash
ssh egov-subhashini 'curl -s -X POST "http://localhost:11234/egov-workflow-v2/egov-wf/businessservice/_search?tenantId=mz&businessServices=PGR" \
  -H "Content-Type: application/json" -d "{\"RequestInfo\":{}}" | head -c 3000'
```

Save the result to `v3-config/pgr-businessservice-2x.json`. This is the source of truth for states, actions and roles.

- [ ] **Step 2: Translate it into a v3 process definition and register it**

Convert states/actions into the v3 shape (`processCode`, states with `stateSlaMinutes`, actions with `roles` and `assigneeCheck`) in `v3-config/pgr-process-definition.yaml`, then register with `digit create-workflow --file …` (see `digit create-workflow --help`). **Record every CCRS concept v3 cannot express** — start from the known ones: the actions `ESCALATE`, `SLA_ESCALATE` and `COMMENT`, the states `PENDINGATSUPERVISOR` and `CANCELLED`, and escalation up the HRMS `reportingTo` chain — with one `gaplog` entry each.

- [ ] **Step 3: Verify the definition is live**

Run: `ssh egov-subhashini 'curl -s -H "X-Tenant-ID: mz" http://localhost:8000/workflow/v3/process/definition | head -c 400'`
Expected: the PGR definition, not `{"definitions":[]}`.

- [ ] **Step 4: Write the failing transition test**

```javascript
// test/workflow.test.js
const test = require('node:test');
const assert = require('node:assert');
const wf = require('../src/translators/workflow');

test('maps a 2.x transition to the v3 transition call', () => {
  const req = { RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'mz' } },
    ProcessInstances: [{ tenantId: 'mz', businessService: 'PGR', businessId: 'PGR-123',
                         action: 'ASSIGN', moduleName: 'PGR', assignes: [{ uuid: 'emp-1' }] }] };
  const out = wf.toV3(req);
  assert.strictEqual(out.method, 'POST');
  assert.strictEqual(out.path, '/workflow/v3/transition');
  assert.strictEqual(out.body.processCode, 'PGR');
  assert.strictEqual(out.body.action, 'ASSIGN');
});

test('rebuilds the 2.x ProcessInstances envelope', () => {
  const v2 = wf.toV2({ state: { code: 'PENDINGFORASSIGNMENT' } }, { ProcessInstances: [{ businessId: 'PGR-123' }] });
  assert.strictEqual(v2.ProcessInstances[0].state.code, 'PENDINGFORASSIGNMENT');
});
```

Correct the v3 field names against the spec at `digit-specs/v3.0.0/workflow.yaml` before implementing.

- [ ] **Step 5: Run it and watch it fail**

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/workflow.test.js'` → FAIL.

- [ ] **Step 6: Implement all four call shapes, then run the tests**

Dispatch inside `toV3` on the inbound path and query string. Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/'` → PASS.

- [ ] **Step 7: Switch, re-run the lifecycle, diff**

Set `EGOV_WORKFLOW_HOST: http://hybrid-shim:9100/`, restart pgr-services, re-run `lifecycle.sh` with `OUT=/opt/hybrid-shim/runs/workflow-$(date +%H%M).json`. Expect assign/resolve to be where breakage shows. Capture exactly which transition fails and why.

- [ ] **Step 8: Commit**

```bash
cd /root/code/Citizen-Complaint-Resolution-System && cp -r /opt/hybrid-shim/src /opt/hybrid-shim/test /opt/hybrid-shim/v3-config hybrid/ && \
  git add hybrid && git commit -m "feat(hybrid): workflow translator and v3 PGR process definition"
```

---

### Task 6: individual/user translator

**Files:**
- Create: `/opt/hybrid-shim/src/translators/user.js`, `/opt/hybrid-shim/test/user.test.js`
- Modify: `/opt/hybrid-shim/src/server.js`, `/opt/digit/docker-compose.egov-digit.yaml` (`EGOV_USER_HOST`)

**Interfaces:**
- Consumes: Task 2 modules.
- Produces: `translators/user.js` exporting `{toV3, toV2}` at prefix `/user`.

CCRS calls `POST /user/_search` with `UserSearchRequest`, and `POST /user/users/_createnovalidate` with `CreateUserRequest` (`service/UserService.java:130,189,212,237,261,282`). v3 splits this: `individual` holds the person (PII encrypted in Vault), Keycloak holds the login.

- [ ] **Step 1: Capture the v3 individual contract**

```bash
ssh egov-subhashini 'curl -s -H "X-Tenant-ID: mz" -H "X-User-ID: 00000000-0000-0000-0000-000000000000" \
  "http://localhost:8000/individual/v3/individuals" | head -c 400'
```

- [ ] **Step 2: Write the failing test for citizen search-by-mobile**

```javascript
// test/user.test.js
const test = require('node:test');
const assert = require('node:assert');
const user = require('../src/translators/user');

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
```

- [ ] **Step 3: Run it and watch it fail**

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/user.test.js'` → FAIL.

- [ ] **Step 4: Implement search and create, then run the tests**

CCRS's `User` object needs `id`, `uuid`, `userName`, `mobileNumber`, `type`, `roles[]` and `tenantId`. v3's individual has no roles and no `type`. Log a `gaplog` entry for each field faked, and note in the detail that roles really live in Keycloak. Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node --test test/'` → PASS.

- [ ] **Step 5: Switch, re-run the lifecycle, diff**

Set `EGOV_USER_HOST: http://hybrid-shim:9100/`, restart pgr-services, re-run `lifecycle.sh` with `OUT=/opt/hybrid-shim/runs/user-$(date +%H%M).json`.

**Expect this one to fail**, because CCRS's own login issues a 2.x token that v3 knows nothing about. That failure, precisely described, is the task's real output: it is the identity-migration requirement.

- [ ] **Step 6: Commit**

```bash
cd /root/code/Citizen-Complaint-Resolution-System && cp -r /opt/hybrid-shim/src /opt/hybrid-shim/test hybrid/ && \
  git add hybrid && git commit -m "feat(hybrid): user/individual translator"
```

---

### Task 7: Gap report

**Files:**
- Create: `/opt/hybrid-shim/report.js`
- Create: `docs/superpowers/specs/2026-09-20-ccrs-digit3-hybrid-findings.md`

**Interfaces:**
- Consumes: `gaps.jsonl` and every file under `/opt/hybrid-shim/runs/`.
- Produces: a findings document grouped by dependency.

- [ ] **Step 1: Write `report.js`**

Read `gaps.jsonl`, group by `dep`, and print a markdown table of `field | action | detail | count`.

- [ ] **Step 2: Run it**

Run: `ssh egov-subhashini 'cd /opt/hybrid-shim && node report.js'`
Expected: one section per dependency touched.

- [ ] **Step 3: Write the findings document**

For each of idgen, boundary, workflow and user: did the lifecycle pass, what was faked, and what would a real migration have to build? State plainly which of the four can be served by DIGIT 3 today.

- [ ] **Step 4: Commit**

```bash
cd /root/code/Citizen-Complaint-Resolution-System && cp /opt/hybrid-shim/report.js hybrid/ && \
  git add hybrid docs/superpowers/specs/2026-09-20-ccrs-digit3-hybrid-findings.md && \
  git commit -m "docs: hybrid findings and gap report"
```
