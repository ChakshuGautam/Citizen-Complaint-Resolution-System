# CCRS 2.x baseline lifecycle

`lifecycle.sh` drives one PGR complaint through **create → assign → resolve** against
the unmodified CCRS (DIGIT 2.12) stack, over the public 2.x API surface only
(Kong gateway). It makes no DB writes and changes no container config; the only
state it creates is one complaint per run.

Exit code is 0 only when every step passes. Each HTTP call appends one JSON
record to `$OUT`, and a normalized comparison view to `$OUT.view`, so a later
run with a dependency re-pointed at the hybrid shim can be compared against this
baseline — see **Diffing two runs** below.

```bash
# record a run
OUT=/opt/hybrid-shim/baseline/baseline-$(date +%Y%m%d-%H%M).json \
  bash /opt/hybrid-shim/baseline/lifecycle.sh

# compare a later run against the baseline (exit 0 == no regression)
bash /opt/hybrid-shim/baseline/compare.sh \
  /opt/hybrid-shim/baseline/baseline-20260920-0238.json /tmp/run.json
```

| file | role |
|---|---|
| `lifecycle.sh` | drives the lifecycle, writes `$OUT` and `$OUT.view` |
| `compare.sh` | normalizes and compares two runs; owns the volatile-field list |
| `baseline-20260920-0238.json` (+ `.view`) | the reference run everything is measured against |

## Values that actually work (discovered, verified 2026-09-20)

Everything is an env override with these defaults:

| var | value | why |
|---|---|---|
| `KONG` | `http://localhost:18000` | the 2.x Kong gateway |
| `TENANT` | `pg` | see "Tenant" below |
| `SERVICE_CODE` | `BurningOfGarbage` | `RAINMAKER-PGR.ComplaintHierarchy` leaf (`levelCode=SUB_TYPE`) on `pg` |
| `LOCALITY` | `SUN01_LOCALITY` | boundary code registered for `pg` in `boundary-service` |
| `SOURCE` | `web` | must be in pgr-services `allowed.source` (`whatsapp,web,mobile,RB Bot`) |
| `CITIZEN_USER` / `CITIZEN_PASS` | `9999009999` / `123456` | the only `CITIZEN` row in `eg_user` (uuid `f1a94c66-a4b5-46da-9492-07b7db54c69f`, tenant `pg`) |
| `EMP_USER` / `EMP_PASS` | `PGGRO1` / `eGov@123` | HRMS employee on `pg`, uuid `21fc9b81-9e52-43a5-ac1a-00db84dc2a6a`, holds **GRO + PGR_LME + CSR** — one user can therefore do both ASSIGN and RESOLVE |
| `ASSIGNEE_UUID` | *(empty)* | defaults to the employee's own uuid |

Tokens come from `POST $KONG/user/oauth/token` with
`Authorization: Basic ZWdvdi11c2VyLWNsaWVudDo=` (`egov-user-client:`),
`userType=CITIZEN` or `userType=EMPLOYEE`.

### Gateway port

The CCRS 2.x Kong is `kong-gateway` on **`127.0.0.1:18000`**. This matches
`url` in `/opt/digit/postman/complaints-demo.postman_collection.json`.

Port **11234**, which the plan originally specified, is `egov-enc-service`
(`127.0.0.1:11234->1234/tcp`); it answers `/user/oauth/token` with a Tomcat
**404** HTML page. The plan and the task briefs have since been corrected to
18000, so this note is only here to save the next person the same lookup.

### Citizen auth caveat

Citizen login at `pg` returns **200 for any password** — citizen auth is
OTP-mode and the OTP is not enforced in this deployment. `123456` is used
because the brief names it, but the password is not actually being checked.
Employee auth *is* checked (wrong password → 400).

## Tenant: `pg`, not `mz`

The plan's Global Constraints name `mz` as the state-level tenant, and
pgr-services is indeed configured with `EGOV_STATE_LEVEL_TENANT_ID=mz`. But
**`mz` cannot complete a PGR lifecycle on the unmodified stack today.** It is a
half-onboarded tenant: users, roles, and the PGR `BusinessService` exist, the
PGR master and geography data do not. Verified:

1. **No complaint types.** MDMS v1
   `POST /egov-mdms-service/v1/_search` for `RAINMAKER-PGR.ComplaintHierarchy`
   returns `{"ResponseInfo":null,"MdmsRes":{}}` for both `mz` and `mz.maputo`.
   In `eg_mdms_data`, `mz` has only `RAINMAKER-PGR.UIConstants`; `pg` has
   `ComplaintHierarchy` (8 rows), `ComplaintHierarchyDefinition`,
   `EscalationConfig`, `RejectionReasons`.
   → `ServiceRequestValidator.validateMDMS` would throw `INVALID_SERVICECODE`.
2. **No boundaries.** `boundary-service/boundary/_search?tenantId=mz.maputo`
   returns `"Boundary":[]`; `boundary-relationships/_search` returns
   `HIERARCHY_DEFINITION_DOES_NOT_EXIST_ERR`. The `boundary` table holds rows
   only for `pg` (7) and `pg.citya` (6).
   → `validateBoundary` throws `INVALID_BOUNDARY_CODE` (observed live).
3. **No citizen.** `eg_user` has zero `CITIZEN` rows on `mz`/`mz.maputo`; the
   only ones are on `pg`. Running `TENANT=mz.maputo` fails at step 1 with
   `FAIL citizen_auth 400`.

Fixing this means seeding MDMS master data and a boundary hierarchy for `mz` —
a change to CCRS configuration, which Task 1 is not allowed to make. `pg` is the
fully-seeded demo tenant, is what the existing
`complaints-demo.postman_collection.json` targets (`stateTenant=pg`), and is the
only tenant that passes unmodified. Every tenant-dependent value is an env var,
so switching to `mz` later is `TENANT=mz.maputo SERVICE_CODE=… LOCALITY=…`
once `mz` is seeded.

## Workflow actions (taken from the live BusinessService, not guessed)

`GET /egov-workflow-v2/egov-wf/businessservice/_search?tenantId=mz&businessServices=PGR`
(the definition is registered under `mz` **and** `pg`):

```
START                 --APPLY[CITIZEN,CSR]-------------> PENDINGFORASSIGNMENT
PENDINGFORASSIGNMENT  --ASSIGN[GRO,PGR_VIEWER]---------> PENDINGATLME
PENDINGFORASSIGNMENT  --REJECT[GRO,PGR_VIEWER]---------> REJECTED
PENDINGATLME          --RESOLVE[GRO,PGR_LME,PGR_VIEWER]-> RESOLVED
PENDINGATLME          --REASSIGN[...]-----------------> PENDINGFORREASSIGNMENT
RESOLVED              --RATE[CFC,CITIZEN]-------------> CLOSEDAFTERRESOLUTION
```

So the script asserts `PENDINGFORASSIGNMENT → PENDINGATLME → RESOLVED`.

`_update` requires the **whole** `service` object returned by the previous call
to be echoed back alongside `workflow`; the script feeds each response into the
next action.

## Output format

One JSON object per line in `$OUT`:

```json
{"body": {...}, "http_code": 200, "step": "create"}
```

Steps, in order: `citizen_auth`, `create`, `search_after_create`,
`employee_auth`, `assign`, `resolve`, `search_final` — 7 records.

`$OUT` keeps **full fidelity** — real ids, real timestamps. That is what you
want when investigating a gap. It is deliberately *not* the thing you diff.

## Diffing two runs

Raw `$OUT` files cannot be diffed directly: every run mints a new
`serviceRequestId` and fresh `createdTime`/`lastModifiedTime`, so a literal
diff is ~100% noise. Two mechanisms fix that, both driven by one ignore list
that lives in `compare.sh`:

**1. `$OUT.view` — plain-diffable.** `lifecycle.sh` writes it automatically
next to `$OUT`. It is the normalized comparison view: one fact per line,
`step<TAB>path<TAB>value`, sorted, with volatile values replaced by
`"<volatile>"`. `diff baseline.json.view run.json.view` on two genuinely
different good runs is **byte-identical** (verified).

**2. `compare.sh` — semantic report.**

```bash
bash compare.sh baseline-20260920-0238.json /tmp/run.json   # exit 0 == equivalent
bash compare.sh --view /tmp/run.json                        # print the view
```

Exit 0 and `EQUIVALENT: 7 steps, 731 compared facts`, or exit 1 and a report
grouped into: steps that did not run (collapsed to one line each — a run that
aborts early otherwise buries its own cause under the whole baseline tail),
changed values, fields missing from the run, and fields new in the run. Long
lists are capped at `COMPARE_MAX_LINES` (default 25). Exit 2 is a usage/IO
error, so a broken invocation is never mistaken for "equivalent".

### What is compared

Everything: **731 facts** per run. Per step, the HTTP code, the step's position
in the sequence, and every leaf value in the response body by full path —
including `null`s, empty objects and empty lists, so a shim that drops or
renames a field shows up as `MISSING FROM RUN` / `NEW IN RUN`.

### What is ignored, and why

Volatility is decided **by path, not by key name**. That matters: a blanket
"ignore anything called `id`" would also blind the comparison to
`citizen_auth.UserRequest.id`, which is what pins *which* citizen authenticated.

| ignored path pattern | why |
|---|---|
| `*.expires_in` | token lifetime, counts down in real time |
| `*.auditDetails.createdTime` | epoch millis |
| `*.auditDetails.lastModifiedTime` | epoch millis |
| `*.serviceRequestId` | `PG-PGR-<date>-<counter>`, new per complaint |
| `*.businessId` | same value as `serviceRequestId` |
| `*.service.id` | server-generated uuid |
| `*.service.address.id` | server-generated uuid |
| `*.processInstance.id` | server-generated uuid |
| `*.businesssServiceSla` | SLA remaining, counts down (upstream spells it with three s's) |
| `*.responseInfo.ts`, `*.ResponseInfo.ts` | intermittently null or epoch millis |

This list was **derived, not guessed**: five real runs were diffed against each
other and every varying leaf path is covered — 41 of 717. `responseInfo.ts` was
added after it flipped between two otherwise identical runs even though five
consecutive runs had agreed on it; anything that is "sometimes null, sometimes a
timestamp" is noise by definition.

If a later task sees a difference it believes is noise, the fix is to add the
path here **with a reason** — not to loosen the comparison globally.

Three deliberate deviations from the brief:

- The brief's prose describes the record as `{step, status, http_code, body}`
  but the `record()` helper it supplies verbatim writes `{step, http_code, body}`.
  The supplied code wins; PASS/FAIL is on stdout instead.
- `access_token` / `refresh_token` in the two auth bodies are replaced with
  `"<redacted>"` before recording, so the artifact holds no live credential and
  auth records diff cleanly across runs.
- `lifecycle.sh` also writes `$OUT.view` and ships `compare.sh` alongside, which
  the brief did not ask for. Without them the promise that "later tasks re-run
  this script and diff against the baseline" cannot actually be kept.

## Baseline run

`/opt/hybrid-shim/baseline/baseline-20260920-0238.json` (+ `.view`) — 7/7 steps
HTTP 200, exit 0, complaint `PG-PGR-2026-09-20-005172`.

Re-run nine more times since, each a fresh complaint: all exit 0, and
`compare.sh` reports `EQUIVALENT` against the baseline for every one. `diff` of
the `.view` files is byte-identical across genuinely different runs.

### compare.sh actually detects regressions

Each case below is a copy of a good run, altered by hand, except the last which
is a real broken run:

| case | result |
|---|---|
| `search_final` `applicationStatus` `RESOLVED`→`PENDINGATLME` | exit 1, `CHANGED VALUES (1)` naming that exact path |
| `assign` `http_code` `200`→`500` | exit 1, `CHANGED VALUES (1)`: `@http_code` |
| `create` `service.source` deleted (what a lossy shim does) | exit 1, `MISSING FROM RUN (1)`: `service.source = "web"` |
| real run with `SERVICE_CODE=NoSuchComplaintType` | exit 1, `STEPS THAT DID NOT RUN (5)` + `create @http_code 200→400` |

### The script actually fails when it should

Checked, so a green run downstream means something:

| injected fault | result |
|---|---|
| `SERVICE_CODE=NoSuchComplaintType` | `FAIL create 400` (`INVALID_SERVICECODE`), exit 1 |
| `LOCALITY=NOPE` | `FAIL create 400` (`INVALID_BOUNDARY_CODE`), exit 1 |
| `EMP_USER=PGWCSR4` (CSR only, no GRO) | `FAIL assign 400` (`INVALID ROLE`), exit 1 |
| `TENANT=mz.maputo` | `FAIL citizen_auth 400`, exit 1 |

### Test data left behind

One complaint per run on tenant `pg`, status `RESOLVED` (`RATE` is not driven,
so it stops short of `CLOSEDAFTERRESOLUTION`). Nothing else is written.
</content>
</invoke>

## `inbox-check.sh` — the multi-complaint employee inbox

`lifecycle.sh` is deliberately left alone (it has to stay comparable against
`baseline-20260920-0238.json`), and there is one CCRS behaviour it structurally
cannot see.

`WorkflowService.enrichWorkflow` (`service/WorkflowService.java:131-145`) joins
**every** complaint in a search result to its workflow in **one** call:

```
POST {wf}/egov-workflow-v2/egov-wf/process/_search?businessIds=<csv>
...
if (processInstances.size() != serviceRequestIds.size())
    throw new CustomException("WORKFLOW_NOT_FOUND", "The workflow object is not found");
```

Both of `lifecycle.sh`'s searches are scoped to a single `serviceRequestId`, so
that csv always has exactly one element and the check always passes — no matter
what is serving the workflow. `inbox-check.sh` is the missing instrument:

1. citizen auth
2. create complaint **A**
3. create complaint **B**
4. employee auth
5. `POST /pgr-services/v2/request/_search?tenantId=pg&ids=<A>,<B>` — an
   inbox-shaped employee query returning **two** complaints
6. assert **both** come back

```bash
OUT=/opt/hybrid-shim/runs/inbox-2x.json bash inbox-check.sh   # exit 0 only if both returned
```

Measured 2026-09-20, same box, same minute, only `EGOV_WORKFLOW_HOST` changed:

| workflow host | result |
|---|---|
| `http://egov-workflow-proxy:8109/` (2.x) | HTTP 200, 2 wrappers, **PASS** |
| `http://hybrid-shim:9100/` (DIGIT 3.0) | HTTP 400 `WORKFLOW_NOT_FOUND`, **FAIL** |

v3's `GET /workflow/v3/transition` serves exactly one `entityId`: the comma
form yields `totalCount 0` and the repeated-parameter form honours only the
first value. So every employee inbox holding two or more complaints is dead on
v3, while the lifecycle stays green.

### Why the query is scoped with `ids=`, not `applicationStatus=`

The first version searched `?tenantId=pg&applicationStatus=PENDINGFORASSIGNMENT`
and failed with the **same** `WORKFLOW_NOT_FOUND` on **both** engines — which
would have made it a broken instrument rather than a finding. The reason is
worth recording on its own: this tenant now holds complaints whose process
instances live in the *other* engine's store (everything created while
`EGOV_WORKFLOW_HOST` pointed at the shim is in DIGIT 3.0; everything older is in
`egov-workflow-v2`). **Workflow state was never migrated between the two**, so
any tenant-wide query spans both stores and neither can answer it. Flipping
`EGOV_WORKFLOW_HOST` is not reversible for complaints already in flight.

Scoping to the two complaints the script itself just created keeps both rows in
whichever store is currently wired in, leaving the multi-id join as the only
variable.

### Test data left behind

Two extra complaints per run, both parked in `PENDINGFORASSIGNMENT`, plus the
usual idgen sequence consumption. Same footprint as `lifecycle.sh`, doubled.
