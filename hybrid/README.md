# hybrid-shim

A stateless 2.x → v3 translation shim. CCRS (DIGIT 2.12) keeps talking its own
API; the shim rewrites each call into DIGIT 3.0 REST, calls the v3 Kong, and
rebuilds the 2.x response envelope. CCRS changes only `EGOV_*_HOST`.

Lives at `/opt/hybrid-shim` on **egov-subhashini (37.27.254.159)**, compose
project `hybrid`, port **9100**, attached to both `digit_egov-network` (2.x)
and `digit3_default` (v3). Rebuild after a code change:

```bash
cd /opt/hybrid-shim && docker compose -p hybrid up -d --build
```

| path | role |
|---|---|
| `src/server.js` | :9100, routes on the inbound 2.x path, registers translators |
| `src/router.js` | longest-prefix router; register prefixes **without** a trailing slash |
| `src/context.js` | `RequestInfo` → `X-Tenant-ID` / `X-User-ID` |
| `src/client.js` | JSON client for `http://d3-kong:8000` |
| `src/gaplog.js` | append-only `gaps.jsonl`; a silent default is a bug |
| `src/translators/*.js` | one `{toV3, toV2}` per re-pointed dependency |
| `baseline/` | the 2.x lifecycle harness — see `baseline/README.md` |

---

## Re-pointed dependencies

| 2.x dependency | prefix | v3 service | status |
|---|---|---|---|
| `egov-idgen` | `/egov-idgen` | `idgen` (`/idgen/v3/*`) | **live** — pgr-services only |
| `boundary-service` | `/boundary-service` | `boundary` (`/boundary/v3/*`) | **live** — pgr-services only |
| `egov-workflow-v2` | `/egov-workflow-v2` | `workflow` (`/workflow/v3/*`) | **live** — pgr-services only; lifecycle passes, see the divergence below |
| `egov-user` | `/user` | `individual` (`/individual/v3/*`) | **REVERTED** — translator written, tested and working, but CCRS cannot use it: see below |

### idgen (Task 3)

`pgr-services` posts `{EGOV_IDGEN_HOST}egov-idgen/id/_generate`
(`egov.idgen.path`) and reads `idResponses[].id`. The shim maps that to
`POST /idgen/v3/generate` (or `/idgen/v3/generate/bulk` when the 2.x batch asks
for more than one id). Field-by-field notes, all probed live and not guessed,
are in the header comment of `src/translators/idgen.js`.

**The template must exist in v3 before generation works.** Its shape is taken
from the live 2.x source of truth — MDMS `common-masters.IdFormat` at tenant
`pg`, `idname = pgr.servicerequestid`, `format = PG-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]`
— *not* from the `PB-PGR-...` default in `pgr-services/application.properties`,
because the live 2.x idgen prefers MDMS over the format the caller sends
(verified: a call sending `PB-PGR-…` returned `PG-PGR-2026-09-20-005216`).

Registered once with **exactly** this command (also kept as
`register-idgen-template.sh`):

```bash
curl -s -m 15 -X POST \
  -H 'X-Tenant-ID: pg' \
  -H 'X-User-ID: hybrid-shim-task3' \
  -H 'Content-Type: application/json' \
  -d '{"templateCode":"pgr.servicerequestid","config":{"template":"PG-PGR-{DATE:yyyy-MM-dd}-{SEQ}","sequence":{"scope":"GLOBAL","start":6000,"padding":{"length":6,"char":"0"}}}}' \
  http://localhost:8000/idgen/v3/template
```

Token translation is by hand: `[cy:yyyy-MM-dd]` → `{DATE:yyyy-MM-dd}`,
`[SEQ_EG_PGR_ID]` → `{SEQ}`. `padding` **must** be an object — v3's binder
silently ignores `paddingLength` / `paddingChar` and leaves `padding: null`.
`start: 6000` is a hand-picked offset above the live 2.x counter; see the gap
log for why that is fragile.

Then record the registration-time gaps (once per fresh v3 template store):

```bash
node /opt/hybrid-shim/record-template-gaps.js
```

Useful neighbours, discovered while probing:

```bash
# list / fetch
curl -H 'X-Tenant-ID: pg' -H 'X-User-ID: me' http://localhost:8000/idgen/v3/template
curl -H 'X-Tenant-ID: pg' -H 'X-User-ID: me' 'http://localhost:8000/idgen/v3/template?templateCode=pgr.servicerequestid'
# delete - BOTH templateCode and version are required, as query params
curl -X DELETE -H 'X-Tenant-ID: pg' -H 'X-User-ID: me' \
  'http://localhost:8000/idgen/v3/template?templateCode=zz.probe&version=v1'
```

#### Switching CCRS over, and back

`EGOV_IDGEN_HOST` appears four times in
`/opt/digit/docker-compose.egov-digit.yaml` — `egov-user` (972),
`boundary-service` (1203), `egov-hrms` (1510) and `pgr-services` (1808).
**Only the pgr-services one is re-pointed.** Original value:
`http://egov-idgen:8088/` (the trailing slash matters — CCRS concatenates host
and path).

`flip_idgen.py` edits that one line and refuses if it cannot find exactly one
match inside the `pgr-services:` block:

```bash
python3 /opt/hybrid-shim/flip_idgen.py shim   # -> http://hybrid-shim:9100/
python3 /opt/hybrid-shim/flip_idgen.py orig   # -> http://egov-idgen:8088/  (rollback)

cd /opt/digit && docker compose -p digit \
  -f docker-compose.egov-digit.yaml -f docker-compose.fast-path.yml \
  -f docker-compose.migrations.yml -f docker-compose.monitoring.yml \
  -f docker-compose.matomo.yml up -d pgr-services
```

The full `-f` list is required: the `digit` project was created from all five
files, and restarting with fewer would drop the others' overrides. A pristine
copy of the file as it was before Task 3 is kept at
`/opt/hybrid-shim/docker-compose.egov-digit.yaml.task3-orig`.

#### Proof it is really v3 serving the ids

The v3 template's counter starts at 6000 while the 2.x sequence
`seq_eg_pgr_id` was at 5216. Complaints created after the switchover are
`PG-PGR-2026-09-20-006001` and `…-006002` — numbers the 2.x idgen could not
have produced.

```bash
OUT=/opt/hybrid-shim/runs/idgen-$(date +%H%M).json bash /opt/hybrid-shim/baseline/lifecycle.sh
bash /opt/hybrid-shim/baseline/compare.sh \
  /opt/hybrid-shim/baseline/baseline-20260920-0238.json /opt/hybrid-shim/runs/idgen-XXXX.json
```

Both runs: 7/7 PASS, `EQUIVALENT: 7 steps, 731 compared facts`, with
`compare.sh`'s ignore list **unchanged**.


### boundary (Task 4)

**`EGOV_BOUNDARY_HOST` serves TWO endpoints, not one.** Moving the variable
moves both, so both must be translated or the forgotten one gets the wrong
envelope:

| 2.x path | CCRS caller | v3 |
|---|---|---|
| `/boundary-service/boundary/_search` | `ServiceRequestValidator.validateBoundary` — is this complaint locality real? | `GET /boundary/v3/boundaries` |
| `/boundary-service/boundary-relationships/_search` | `BoundaryHierarchyExpander.fetch` — which boundary codes may this employee see? | `GET /boundary/v3/relationship` |

`src/translators/boundary.js` dispatches on the sub-path and **throws** on any
third `/boundary-service/*` path (502 `SHIM_ERROR`) rather than guessing. The
first switchover attempt did guess — one translator for the whole prefix — and
the relationship call came back as a boundary envelope, so the employee's
jurisdiction expanded to the empty set and the final search returned **zero
complaints with HTTP 200 and no error anywhere**. Field-by-field notes, all
probed live, are in the header comment of the translator.

Two quirks worth knowing:

- The 2.x boundary search posts a **bare `RequestInfo`** as its body (not
  `{"RequestInfo":{...}}`) and puts the criteria in the query string, so
  `context.toHeaders()` yields an empty tenant. The translator builds
  `X-Tenant-ID` / `X-User-ID` itself, from `?tenantId=` first and the
  `userInfo` second. The relationship search *does* wrap it; both are handled.
- `egov.boundary.host` ends in `/` and `egov.boundary.search.url` starts with
  `/`, so the concatenation has a double slash — but Spring's `RestTemplate`
  normalises it before the wire (confirmed in boundary-service's own
  `TracerFilter` log; 2.x actually **500s** on the un-normalised form). The
  shim therefore sees a single slash and the registered prefix matches.

#### Seeding v3 first — the store starts empty

Nothing migrates 2.x boundary data. `GET /boundary/v3/boundaries` returned
`{"boundary":[]}` for every `pg` code, so CCRS could not validate a locality
until the hierarchy and boundaries existed in v3. Seeded with **exactly** this
script, transcribed from the live CCRS `boundary` / `boundary_relationship`
tables (`ADMIN`: `PG_STATE` > `PG_CITYA` > `PG_CITYA_Z1` > `PG_CITYA_B1` >
`SUN01_LOCALITY`, the chain the baseline's `LOCALITY=SUN01_LOCALITY` needs):

```bash
bash /opt/hybrid-shim/seed-boundaries.sh          # V3=, TENANT= are overridable
node /opt/hybrid-shim/record-boundary-gaps.js     # idempotent; once per fresh store
```

Only that chain was seeded. `SUN02_LOCALITY`, `SUN03_LOCALITY` and the whole
`pg.citya` tenant exist in 2.x and **do not exist in v3** — see the gap log.

#### Switching CCRS over, and back

`EGOV_BOUNDARY_HOST` appears three times in
`/opt/digit/docker-compose.egov-digit.yaml` — `egov-hrms` (1514, no trailing
slash), `egov-bndry-mgmnt` (1570) and `pgr-services` (1814). **Only the
pgr-services one is re-pointed.** Original value:
`http://boundary-service:8081/` (the trailing slash matters).

`flip_boundary.py` edits that one line and refuses if it cannot find exactly
one match inside the `pgr-services:` block:

```bash
python3 /opt/hybrid-shim/flip_boundary.py shim   # -> http://hybrid-shim:9100/
python3 /opt/hybrid-shim/flip_boundary.py orig   # -> http://boundary-service:8081/  (rollback)
```

then restart pgr-services with the **full five-file** `-f` list shown under
idgen above. A pristine copy of the file as it was before Task 4 is kept at
`/opt/hybrid-shim/docker-compose.egov-digit.yaml.task4-orig`.

> **Always `docker restart digit-pgr-services-1` before measuring a boundary
> change.** `BoundaryHierarchyExpander` caches each jurisdiction expansion for
> **30 minutes** (Caffeine `expireAfterWrite`) and falls back silently to the
> unexpanded code on any failure. `docker compose up -d pgr-services` with no
> config change leaves the container running and does **not** evict it, so a
> run made after a fix can still be measuring the state before it. This cost
> two red lifecycle runs during Task 4.

#### Proof it is really v3 serving the boundaries

pgr-services' own log shows every boundary call going to the shim, and the
seeded v3 rows carry `createdBy: hybrid-shim-task4` where the 2.x rows carry
`createdBy: system`:

```bash
docker logs digit-pgr-services-1 --since 15m 2>&1 \
  | grep -oE "hybrid-shim:9100/boundary-service/[a-z-]+/_search" | sort | uniq -c
```

Three consecutive runs: 7/7 PASS, `EQUIVALENT: 7 steps, 731 compared facts`,
with `compare.sh`'s ignore list **unchanged**.

### workflow (Task 5)

**`EGOV_WORKFLOW_HOST` serves THREE 2.x paths, and one of them has three query
shapes.** The inventory was taken from the Java *and* from the
`egov-workflow-proxy` access log during a green lifecycle run — which is how
`history=true`, named in no call site the plan lists, turned up:

| 2.x path + query | CCRS caller | v3 |
|---|---|---|
| `POST …/egov-wf/process/_transition` | `WorkflowService.callWorkFlow:244` | `POST /workflow/v3/transition` |
| `POST …/egov-wf/businessservice/_search?businessServices=PGR` | `WorkflowService:95`, `MigrationUtils:147` | `GET /workflow/v3/process/definition?processCode=` |
| `POST …/egov-wf/process/_search?businessIds=<csv>` | `WorkflowService.enrichWorkflow:131` | `GET /workflow/v3/transition?entityId=` |
| `POST …/egov-wf/process/_search?businessIds=<one>&history=true` | `NotificationService:662`, `EscalationService:277` | `…&history=true` |
| `POST …/egov-wf/process/_search?businessService=&assignee=` | `WorkflowService.getServiceRequestIdsByAssignee:264` | `…&assignee=` |

`src/translators/workflow.js` dispatches on the sub-path and **throws** on any
fourth `/egov-workflow-v2/*` path (502 `SHIM_ERROR`). Field-by-field notes, all
probed live, are in the translator's header comment.

`EGOV_WORKFLOW_HOST` appears **once** in
`/opt/digit/docker-compose.egov-digit.yaml` (line 1809). Original value:
`http://egov-workflow-proxy:8109/` — a pure pass-through nginx
(`/opt/digit/nginx/workflow-proxy.conf`) in front of `egov-workflow-v2:8109`,
so it adds no behaviour of its own.

```bash
python3 /opt/hybrid-shim/flip_workflow.py shim   # -> http://hybrid-shim:9100/
python3 /opt/hybrid-shim/flip_workflow.py orig   # -> http://egov-workflow-proxy:8109/
```
then restart pgr-services with the **full five-file** `-f` list shown under
idgen. A pristine copy of the file as it was before Task 5 is kept at
`/opt/hybrid-shim/docker-compose.egov-digit.yaml.task5-orig`.

> **Verify the restart actually happened.** `docker compose up -d pgr-services`
> can leave the container running. Compare `docker inspect
> digit-pgr-services-1 --format '{{.State.StartedAt}}'` before and after, and
> confirm `EGOV_WORKFLOW_HOST` inside the *running* container.

#### Hostname check

`d3-boundary-service` claims the alias `boundary-service`, so Task 4 had to
check for a collision. The workflow equivalent has none: `d3-workflow-service`
claims `[d3-workflow-service, workflow-service]` on `digit3_default` only,
while `digit-egov-workflow-v2-1` claims `[digit-egov-workflow-v2-1,
egov-workflow-v2]` on `digit_egov-network`.

#### Registering the PGR workflow in v3 first

Nothing migrates the 2.x `BusinessService`. `v3-config/pgr-businessservice-2x.json`
is the live export (tenant `pg`), and `v3-config/pgr-process-definition.yaml` is
it transcribed into the v3 shape — 9 states, 17 actions, roles and SLAs
verbatim.

```bash
bash /opt/hybrid-shim/register-pgr-workflow.sh   # idempotent; V3=, TENANT= overridable
node /opt/hybrid-shim/record-workflow-gaps.js    # idempotent; once per fresh store
curl -s -H 'X-Tenant-ID: pg' -H 'X-User-ID: me' \
  http://localhost:8000/workflow/v3/process/definition | head -c 400
```

`digit create-workflow --file` is **not** used: the CLI is unconfigured on this
box (`digit config show` prints an empty server URL and token) and
`digit config set` authenticates against Keycloak, while this v3 Kong has zero
plugins. `register-pgr-workflow.sh` posts the CLI's payload with curl, exactly
as Task 3 registered the idgen template.

#### Roles: v3 reads them **only** from a Bearer JWT

`ActionInput` in `digit-specs/v3.0.0/workflow.yaml` does not declare `roles` —
but the live service accepts, stores, returns **and enforces** it (403
`caller role does not permit the requested action`). The only channel it reads
roles from is `Authorization: Bearer <jwt>` with `realm_access.roles`; the
`X-Roles` header named in the service's own `Headers` constant is ignored by
this build (verified: `X-Roles` → 403, Bearer → 200), and the signature is not
checked, so `{"alg":"none"}` is honoured. The shim therefore forges an unsigned
token carrying exactly the roles the inbound 2.x `RequestInfo` already asserts.
Registering the actions *without* roles was rejected: a role-less action is open
to everyone, which would delete CCRS's authorisation model silently.

#### Result: 7/7 PASS, and 259 facts that differ

Three consecutive runs, `docker restart` between: **7/7 PASS**, complaint
lifecycle `PENDINGFORASSIGNMENT → PENDINGATLME → RESOLVED`, run views
byte-identical to each other. `compare.sh` against the 2.x baseline reports
**55 changed, 193 missing, 11 new** of 731 facts, and every one of them sits in
`service.processInstance` on the three transition steps. **The two search steps
are byte-identical to the 2.x baseline.** The ignore list was **not** touched —
this is v3 behaving differently, so it lives in `gaps.jsonl`.

```bash
OUT=/opt/hybrid-shim/runs/workflow-$(date +%H%M).json bash /opt/hybrid-shim/baseline/lifecycle.sh
bash /opt/hybrid-shim/baseline/compare.sh \
  /opt/hybrid-shim/baseline/baseline-20260920-0238.json /opt/hybrid-shim/runs/workflow-XXXX.json
# proof the v3 store holds the complaint's whole history:
curl -s -H 'X-Tenant-ID: pg' -H 'X-User-ID: me' \
  'http://localhost:8000/workflow/v3/transition?processCode=PGR&entityId=<SRID>&history=true'
```

#### What DIGIT 3.0 cannot do at all (22 gap entries, `record-workflow-gaps.js`)

Verified live, not inferred from the spec:

- **Self-loop actions are dead.** Any action with `nextState == currentState`
  is stored and then refused at runtime with 409
  `invalid state transition: no transition`. That is **COMMENT** (5 states) and
  **ESCALATE** (PENDINGATLME) — so citizen commenting and auto-escalation are
  both unavailable.
- **`isTerminateState` survives nowhere.** `TERMINAL_SUCCESS`/`TERMINAL_FAILURE`
  are accepted and silently rewritten to `INTERMEDIATE`.
- **`additionalDetails` is accepted and discarded** on process definitions and
  states, so there is no escape hatch for 2.x-only config either.
- **Multi-id search is impossible** — comma form returns `[]`, repeated
  `?entityId=` honours only the first. Any employee search returning more than
  one complaint fails `WORKFLOW_NOT_FOUND` (loudly, by design).
- **`processCode` is mandatory when filtering by `entityId`**, and 2.x's
  businessIds search carries none — the shim invents `PGR` and logs it.
- **Escalation up the HRMS `reportingTo` chain has no v3 model**: `assignees`
  are opaque strings, `EscalationConfig` can only name a fixed action, and no v3
  spec carries a reporting hierarchy.
- `PENDINGATSUPERVISOR` and `SLA_ESCALATE`, which the plan expected, **do not
  exist in this CCRS** — removed on purpose (`docs/migration/pgr-escalation-self-loop.md`);
  both are recorded so the inventory accounts for them.

---

### user / individual (Task 6) — **reverted, and that is the finding**

`EGOV_USER_HOST` is back on `http://egov-user-proxy:8107/`. The translator
(`src/translators/user.js`, 24 tests) is complete and demonstrably works
against the live v3 registry — it is CCRS's *identity* that cannot cross, not
its user *lookups*.

#### The three endpoints, counted two ways

pgr-services builds its user URIs from `egov.user.host` +
`{/user/_search, /user/users/_createnovalidate, /user/users/_updatenovalidate}`
(`application.properties:73-78`), reached from six call sites in
`service/UserService.java`. The `egov-user-proxy` access log, grouped by source
container IP, agrees and adds the two that matter:

| path | source | moves with `EGOV_USER_HOST`? |
|---|---|---|
| `POST /user/_search` | pgr-services (.38) — 477 | **yes** |
| `POST /user/users/_createnovalidate` | pgr-services — 9 | **yes** |
| `POST /user/users/_updatenovalidate` | pgr-services — 1 | **yes** (but unservable) |
| `POST /user/_details?access_token=` | **kong-gateway (.35) — 2696** | **NO** |
| `POST /user/oauth/token` | **kong-gateway — 115** | **NO** |
| `POST /user/v1/_search` | egov-hrms (.36) — 129 | no |

The gateway validates every session through its **own** upstream, not through
this variable. So the flip moves user *lookups* and leaves *authentication*
exactly where it was — which is precisely why it fails.

#### The failure, in one call

```
lifecycle.sh -> PASS citizen_auth -> FAIL create 400
  {"Errors":[{"code":"INVALID_ACCOUNTID",
              "message":"No user exist for the given accountId"}]}
```

1. `POST {kong}/user/oauth/token` (2.x egov-user, untouched) mints a session
   for `9999009999` with `uuid = f1a94c66-a4b5-46da-9492-07b7db54c69f` — a row
   id in the **egov-user** schema.
2. `EnrichmentService.enrichCreateRequest:64` copies it onto the complaint:
   `service.accountId = requestInfo.userInfo.uuid`.
3. `UserService.enrichUser:110` asks for it back:
   `POST http://hybrid-shim:9100/user/_search` with `uuid:[f1a94c66-…]`
   (confirmed in the pgr-services RestTemplate log).
4. The shim translates it faithfully to
   `GET /individual/v3/individuals?id=f1a94c66-…&size=1&page=1`.
   v3 answers `{"totalCount":0,"individuals":[]}`.
5. CCRS throws `INVALID_ACCOUNTID`.

#### Why no shim can fix it

| attempted mapping | what the live service does |
|---|---|
| create the individual **at** the 2.x uuid | `id` in the body is **silently ignored** — sent `aaaaaaaa-…`, got `629b9122-…` (201). Same for `individualId`. |
| store the 2.x uuid in `individual.userId` | stored, but the `?userId=` **filter is ignored**: `?userId=<bogus>` returned *every* individual in the tenant, HTTP 200 |
| store it in `additionalAttributes` | accepted, but not a search filter — write-only |

There is no stateless mapping. A stateful one is a user-migration service.
**The requirement is: migrate every `egov-user` row into the individual
registry, and move CCRS's login to the identity provider v3 already expects —
Keycloak** (`d3-keycloak` is running; `d3-individual` carries
`KEYCLOAK_ENABLED=true`, `KEYCLOAK_ASSIGNABLE_ROLES=ASSIGNER,CITIZEN`).

#### Everything else about the translator works

Probed live through the shim on :9100 before the flip:

* search by mobile → a full CCRS `User` envelope, `HTTP 200`
* create → `HTTP 200`, individual minted, uuid returned
* `_updatenovalidate` → `HTTP 502 SHIM_ERROR` (loud, by design)
* `/user/_details`, `/user/oauth/token`, `/user/v1/_search` → `HTTP 502 SHIM_ERROR`

And one thing v3's individual does **better** than its workflow: the repeated
form `?id=a&id=b` really does OR the values (`?id=<known>&id=<unknown>` →
`totalCount 1`), so `searchBulkUser` — the multi-complaint employee inbox that
Task 5 found dead on the workflow service — *is* servable here. The comma form
`?id=a,b` is not (400), so the translator uses the repeated form.

#### A v3 stack can be healthy and unable to store a person

Every create returned `500 ENCRYPTION_ERROR` until
`register-individual-transit.sh` ran: `d3-individual` encrypts PII through
Vault transit and **the transit engine was never mounted** on this box
(`no handler for route "transit/encrypt/IndividualEncrypt"`). Searches answered
`200` throughout. Nothing in the API surface says so until the first write.

#### Switching CCRS over, and back

```bash
python3 /opt/hybrid-shim/flip_user.py shim      # -> http://hybrid-shim:9100/
python3 /opt/hybrid-shim/flip_user.py orig      # -> http://egov-user-proxy:8107/
cd /opt/digit && docker compose -p digit \
  -f docker-compose.egov-digit.yaml -f docker-compose.fast-path.yml \
  -f docker-compose.migrations.yml -f docker-compose.monitoring.yml \
  -f docker-compose.matomo.yml up -d pgr-services
docker inspect digit-pgr-services-1 --format '{{.State.StartedAt}}'   # CHECK IT
```

`EGOV_USER_HOST` occurs **six** times in the compose file (egov-workflow-v2,
boundary-service, egov-hrms, the bootstrap job …). `flip_user.py` edits only
the copy inside the `pgr-services:` block and asserts there is exactly one.
Pristine pre-Task-6 file: `docker-compose.egov-digit.yaml.task6-orig`.

#### 11 registration-time gap entries — `record-user-gaps.js`

Identity mapping, the ignored-filter trap, the unmounted Vault transit engine,
`gender` mandatory-but-documented-optional, the `^[A-Za-z ]+$` name regex, the
mandatory `version` on update, the size-100 cap, roles/type/userName having no
v3 representation at all, `User.id` having no uuid-registry counterpart, and
`createdDate` being load-bearing for `UserUtils.parseResponse`.

---

### The employee inbox — `baseline/inbox-check.sh`

A second instrument, added because `lifecycle.sh` cannot see the gap Task 5
found: `WorkflowService.enrichWorkflow` joins every complaint in a search
result to its workflow in one `?businessIds=<csv>` call and throws
`WORKFLOW_NOT_FOUND` unless the counts match, but **both** lifecycle searches
are scoped to a single `serviceRequestId`, so the csv is always one element
long. `inbox-check.sh` creates **two** complaints, authenticates as the
employee and searches `?tenantId=pg&ids=<A>,<B>`.

```
on 2.x  (EGOV_WORKFLOW_HOST=egov-workflow-proxy:8109)  HTTP 200, 2 wrappers, PASS
on v3   (EGOV_WORKFLOW_HOST=hybrid-shim:9100)          HTTP 400, WORKFLOW_NOT_FOUND, FAIL
```

Same script, same box, same minute. `lifecycle.sh` was **not** modified, so it
stays comparable to `baseline-20260920-0238.json`.

> **Instrument caveat, recorded because it invalidated the first version:** a
> tenant-wide query (`?applicationStatus=PENDINGFORASSIGNMENT`) fails with the
> *same* `WORKFLOW_NOT_FOUND` on **both** engines, because the tenant now holds
> complaints whose process instances live in the other engine's store —
> workflow state was never migrated between 2.x and v3. Flipping
> `EGOV_WORKFLOW_HOST` is not reversible for complaints already in flight.
> Scoping to the two complaints the script just created is what makes the
> multi-id gap the only variable.

---

## Tests

```bash
cd /opt/hybrid-shim && node --test test/      # 125/125, no npm dependencies
```

`test/idgen.test.js`, `test/boundary.test.js`, `test/workflow.test.js` and `test/user.test.js` redirect `GAP_LOG` to a temp
dir, so running the suite never writes to the real `gaps.jsonl`.

## Gap log

`/opt/hybrid-shim/gaps.jsonl` (a symlink into the container's bind mount at
`gaps/`). One JSON object per line. Every invented default, dropped field or
faked response key lands here — including ones recorded per request, so a
dependency that is quietly lossy shows up as volume, not silence.
