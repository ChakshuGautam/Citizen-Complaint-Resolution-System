# CCRS 2.x baseline lifecycle

`lifecycle.sh` drives one PGR complaint through **create → assign → resolve** against
the unmodified CCRS (DIGIT 2.12) stack, over the public 2.x API surface only
(Kong gateway). It makes no DB writes and changes no container config; the only
state it creates is one complaint per run.

Exit code is 0 only when every step passes. Each HTTP call appends one JSON
record to `$OUT`, so a later run with a dependency re-pointed at the hybrid shim
can be diffed against this baseline.

```bash
OUT=/opt/hybrid-shim/baseline/baseline-$(date +%Y%m%d-%H%M).json \
  bash /opt/hybrid-shim/baseline/lifecycle.sh
```

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

### Port correction

The task brief says the 2.x Kong is on host port **11234**. It is not —
`11234` is `egov-enc-service` (`127.0.0.1:11234->1234/tcp`), which answers
`/user/oauth/token` with a Tomcat **404**. The CCRS Kong is `kong-gateway`
on **`127.0.0.1:18000`**. (This matches `url` in
`/opt/digit/postman/complaints-demo.postman_collection.json`.)

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

Two deliberate deviations from the brief:

- The brief's prose describes the record as `{step, status, http_code, body}`
  but the `record()` helper it supplies verbatim writes `{step, http_code, body}`.
  The supplied code wins; PASS/FAIL is on stdout instead.
- `access_token` / `refresh_token` in the two auth bodies are replaced with
  `"<redacted>"` before recording, so the artifact holds no live credential and
  auth records diff cleanly across runs.

## Baseline run

`/opt/hybrid-shim/baseline/baseline-20260920-0238.json` — 7/7 steps HTTP 200,
exit 0, complaint `PG-PGR-2026-09-20-005172`. Re-run immediately afterwards to
confirm it is repeatable, not a one-off: pass again, exit 0.

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
