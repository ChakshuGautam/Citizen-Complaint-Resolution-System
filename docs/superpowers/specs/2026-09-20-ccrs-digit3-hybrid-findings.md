# CCRS on DIGIT 3.0: what can be served today, and what a real migration has to build

**Date:** 2026-09-20 · **Box:** egov-subhashini (37.27.254.159) · **Tenant:** `pg` · **Audience:** the eGov/DIGIT platform team, and whoever plans the Bomet/Nairobi migration.

CCRS (DIGIT 2.12) was re-pointed at DIGIT 3.0 services one dependency at a time, through a stateless
translation shim, and every invented default, dropped field and faked response key was written to an
append-only log. Four dependencies were attempted: **idgen, boundary, workflow, user/individual**.
Three are still live on v3 as this is written; the fourth was reverted and cannot be flipped at all.

This document says, per dependency: what the lifecycle actually proved, what had to be faked to get
there, and — the distinction that matters most — what is **unservable** as opposed to merely
**unimplemented**.

---

## 1. Bottom line

| dependency | live on v3 now? | complaint lifecycle | verdict |
|---|---|---|---|
| **idgen** | yes | green | **Ready today** for PGR, with an ID-authority caveat that is a data problem, not a translation problem |
| **boundary** | yes | green | **Ready today** for single-code lookups, once the geography is migrated |
| **workflow** | yes | green **on the happy path only** | **Not ready.** Kills citizen COMMENT, kills ESCALATE, kills every employee inbox holding >=2 complaints, and the flip is irreversible for in-flight complaints |
| **user / individual** | **no — reverted** | red (`INVALID_ACCOUNTID`) | **Not ready, and not a shim problem.** Identity cannot move piecemeal |
| localization, HRMS, MDMS, enc-service, url-shortening, all Kafka paths | never switched | — | see section 7 |

Two dependencies out of four are genuinely servable by DIGIT 3.0 today. The two that are not fail for
different reasons: workflow because the v3 engine cannot express things CCRS depends on, identity
because authentication does not travel with the environment variable that moves user lookups.

**What this proof does NOT establish** — read section 9 before quoting any of it.

---

## 2. How to read the evidence (and one defect in it)

| artefact | where | what it is |
|---|---|---|
| `gaps.jsonl` | `/opt/hybrid-shim/` | 923 append-only entries; 97 distinct `(dep, field, action)` triples. Entries are written per request as well as at registration time, so counts are call frequencies, not distinct findings |
| `runs/*.json` + `.view` | `/opt/hybrid-shim/runs/` | one lifecycle or inbox run each; `.view` is the normalised, diffable projection |
| `baseline/` | `/opt/hybrid-shim/baseline/` | `lifecycle.sh`, `compare.sh`, `inbox-check.sh` and their READMEs |
| `hybrid/` | CCRS repo, branch `feat/digit3-hybrid` | translators, unit tests, seed/flip/record scripts, v3 config |

### The gap log contains a withdrawn claim. Reading it top-to-bottom will mislead you.

`gaplog.record()` only ever appends — there is no rewrite path — so a claim that was later found to be
overstated is still sitting in the file, unaltered, above its own correction.

- **Withdrawn:** line 526 (`2026-09-20T04:50:08Z`, `egov-user | User.roles[] / User.type | faked`),
  which claims the shim's invented `roles[]` feed privilege decisions.
- **Current:** lines 729 (`05:06:58Z`) and 805 (`05:11:55Z`), same field, action `dropped`, both opening
  `SUPERSEDES an earlier entry`. There are two copies because `record-user-gaps.js` re-runs on every
  shim rebuild.
- **The corrected claim** is in section 6.4: shim-supplied roles are *structurally unable* to reach an
  authorization decision, and their only real consumer is a display string.

Exactly four fields in the whole log carry two different actions, and all four are this same
correction:

```
egov-user | userName                  -> faked, dropped
egov-user | user.type                 -> faked, dropped
egov-user | user.roles[]              -> faked, dropped
egov-user | User.roles[] / User.type  -> faked, dropped
```

**Rule for anyone mining this log: grep the field name, sort by `ts`, take the LAST entry.** Stopping
at the first match gives you a retracted claim. That is a real usability defect in the evidence being
handed over; a future run should either write to a keyed store or mark superseded lines in place.

### Live state, verified today

`docker inspect digit-pgr-services-1`:

```
EGOV_IDGEN_HOST     = http://hybrid-shim:9100/     (v3)
EGOV_BOUNDARY_HOST  = http://hybrid-shim:9100/     (v3)
EGOV_WORKFLOW_HOST  = http://hybrid-shim:9100/     (v3)
EGOV_USER_HOST      = http://egov-user-proxy:8107/ (2.x - reverted)
EGOV_MDMS_HOST / EGOV_LOCALIZATION_HOST / EGOV_HRMS_HOST / EGOV_URL_SHORTNER_HOST / EGOV_ENC_HOST -> 2.x
```

Latest lifecycle run (`runs/task7-audit.json`, complaint `PG-PGR-2026-09-20-900032`): **7/7 PASS,
exit 0**, and its `.view` is **byte-identical** to the previous good run (`runs/workflow-fix1.json.view`).
Against the untouched 2.x baseline, `compare.sh` reports **55 changed / 193 missing / 11 new** of 731
facts — all inside `service.processInstance`, all attributable to the workflow flip (section 5.2). The
ignore list was never widened.

Database proof that this is really v3 and not 2.x:

| check | result |
|---|---|
| `eg_pgr_service_v2` (2.x) | 53 complaints, **31** carrying v3-minted ids (`PG-PGR-2026-09-20-9000xx`) |
| `seq_eg_pgr_id` (2.x idgen sequence) | **5348** — nowhere near the 9000xx ids CCRS is receiving |
| `eg_wf_processinstance_v2` (2.x workflow) | 96 rows, **0** for `PG-PGR-2026-09-20-900032` |
| `process_instances` (v3, d3-postgres) | 42 rows, including **APPLY / ASSIGN / RESOLVE** for that complaint at 05:18:50-51Z, `assignees = ["21fc9b81-9e52-43a5-ac1a-00db84dc2a6a"]` |
| `boundary_v1` / `boundary_relationship_v1` (v3) | 5 / 5 — the seeded `PG_STATE -> ... -> SUN01_LOCALITY` chain the complaint validated against |

---

## 3. idgen — **ready today**

### 3.1 Did the lifecycle pass, and what was proven

Yes. Three consecutive green runs at switchover (Task 3) and every run since, including today's.
What is proven is narrow and solid: **CCRS's only idgen call can be served by DIGIT 3.0 idgen.**

`EGOV_IDGEN_HOST` serves exactly **one** endpoint — `application.properties:84`
(`egov.idgen.path=egov-idgen/id/_generate`), one caller (`IdGenRepository`). There is no second API
hiding behind the variable, which is why this was the easy one.

Evidence: the id CCRS receives cannot have come from 2.x. The v3 template counter starts at 900000;
`seq_eg_pgr_id` is at 5348. 31 of 53 rows in `eg_pgr_service_v2` carry 9000xx ids.

### 3.2 What was faked, dropped or worked around

| field | action | what happened | to build |
|---|---|---|---|
| `format` | dropped (37x) | v3 templates are pre-registered by `templateCode`; no per-request pattern exists. CCRS's `PB-PGR-...` was discarded — as 2.x also discarded it, in favour of MDMS | nothing for the shape; see the source-of-truth note below |
| `responseInfo` | faked (40x) | v3 returns a bare `{templateCode, version, id}`. The whole 2.x envelope is synthesised, `resMsgId` hardcoded to the constant the live 2.x service returns | v3 callers must stop expecting it; 2.x callers need it synthesised |
| `[cy:yyyy-MM-dd]`, `[SEQ_EG_PGR_ID]` | unmapped (L6, L8) | token syntax differs (`{DATE:...}`, `{SEQ}`), and v3's `{SEQ}` is **per template**, not a named shared Postgres sequence | a v3 template that can bind to an existing named sequence, or a one-way cutover with a high-water mark |
| MDMS `common-masters.IdFormat` | unmapped (L9) | the source of truth for ID format moved from MDMS to the v3 template store, silently | a decision and a migration, not code |
| `config.sequence.start` | defaulted (3x) | raised 6000 -> 900000 by hand to defuse a live collision hazard. **Cosmetic** | a shared ID authority |
| `PUT /idgen/v3/template` | unmapped (L28) | v3 refuses to change `start` on a GLOBAL template ("delete and recreate"). Changing it is DELETE + POST — destructive, non-atomic, and discards the counter's high-water mark. 2.x did `ALTER SEQUENCE RESTART` | a reseed operation |
| heterogeneous batch | unmapped | one v3 call carries one `templateCode`. The shim throws rather than mint ids from the wrong template. CCRS never sends one | a multi-template generate |

### 3.3 Unservable vs unimplemented

Nothing here is unservable for PGR. The one structural item is a **data** problem: two independent
allocators over one ID space, separated by a hand-picked offset that nothing enforces, monitors or
reconciles. It is aggravated by `digit-gatus`, which POSTs the 2.x idgen every 60s for an id nobody
consumes — so the 2.x counter keeps climbing after the cutover, and a green gatus board is no longer
evidence that CCRS's id path is healthy.

**A real migration must:** freeze the 2.x sequence, seed the v3 template above its high-water mark as a
one-way cutover, move the format definition out of MDMS into the template store deliberately (and
delete the MDMS entry so nobody edits a dead one), and re-point or retire the monitoring check.

---

## 4. boundary — **ready today, for what CCRS actually asks**

### 4.1 Did the lifecycle pass, and what was proven

Yes, after one red run that is the most instructive result in this whole exercise.

**`EGOV_BOUNDARY_HOST` serves TWO endpoints, not one** — `application.properties:185-186`:

| 2.x path | CCRS caller | v3 |
|---|---|---|
| `/boundary-service/boundary/_search` | `ServiceRequestValidator.validateBoundary` — is this locality real? | `GET /boundary/v3/boundaries` |
| `/boundary-service/boundary-relationships/_search` | `BoundaryHierarchyExpander.fetch` — which codes may this employee see? | `GET /boundary/v3/relationship` |

The first switchover registered one translator for the `/boundary-service` prefix. The relationship
call was answered with a *boundary* envelope, which has no `TenantBoundary` key;
`BoundaryHierarchyExpander` expanded the employee's jurisdiction to the empty set; the employee search
returned **zero complaints, HTTP 200, no error anywhere** (gap log L80). That failure mode — a
plausible envelope answered to a call nobody translated — is the thing this whole exercise exists to
surface, and it recurs below.

Two consequences worth carrying into any migration plan:

1. **A dependency cannot be re-pointed one API at a time.** Count the endpoints behind every
   `EGOV_*_HOST` before assuming one translator is enough.
2. **`BoundaryHierarchyExpander` caches each expansion for 30 minutes and falls back silently on
   failure** (L81), so a boundary regression is invisible for half an hour — and
   `docker compose up -d pgr-services` does **not** restart the container when no config changed;
   `docker restart digit-pgr-services-1` is required before measuring anything.

### 4.2 What was faked, dropped or worked around

| field | action | what happened | to build |
|---|---|---|---|
| `tenantBoundary` -> `TenantBoundary` | defaulted (7x) | key case only — and this single rename is all that stands between a working employee search and a blank one | nothing; it is a rename |
| `boundary` -> `Boundary`, `additionalAttributes` -> `additionalDetails`, `modifiedBy/Time` -> `lastModifiedBy/Time` | defaulted (35x) | renames, values carried across untouched | a naming decision |
| `ResponseInfo` | faked (43x) | no ResponseInfo exists anywhere in DIGIT 3.0 | as idgen |
| lowercase `boundary` alias | faked (36x) | the list is emitted twice, deliberately, because a brief-specified test asserts the lowercase key. Safe only because pgr-services disables `FAIL_ON_UNKNOWN_PROPERTIES`; a strict parser would reject it | remove the alias |
| relationship-node `auditDetails` | dropped (7x) | v3 attaches it to every node; 2.x emits none on that endpoint | nothing |
| leaf `children` | defaulted (7x) | v3 omits `children` on a leaf, 2.x emits `[]` | nothing for CCRS; a consumer iterating unguarded would break |
| boundary data | unmapped (L82) | **the v3 boundary store was empty.** Nothing migrates 2.x geography. Five boundaries and five relationships were transcribed by hand from the live 2.x tables | a boundary migration |
| `codes` (multi-value) | unmapped (L83) | 2.x `?codes=A,B` returns both; v3 returns `[]` for the same string and for `?codes=A&codes=B`, though `boundary.yaml` declares the parameter as a comma-separated array | fix v3, or one call per code |

### 4.3 Unservable vs unimplemented

- **Unimplemented (v3 bug):** multi-code search. The spec says it should work; the build matches the
  raw string as one code. CCRS never sends more than one, so it is latent — but any other 2.x consumer
  doing a multi-code lookup is blocked today.
- **Unimplemented (migration work):** the geography itself. v3 holds one chain for `pg`;
  `SUN02/SUN03_LOCALITY` and the whole `pg.citya` tenant exist only in 2.x. The lifecycle passes
  because it uses the one seeded locality. A broader test would fail, correctly.
- **Unservable:** nothing found.

**A real migration must:** migrate the boundary hierarchy per tenant, fix (or avoid) multi-code search,
and translate four key names. That is a short list, and it is why boundary is the second dependency
that is genuinely ready.

---

## 5. workflow — **green on the happy path, and not ready**

### 5.1 Did the lifecycle pass, and what was proven

Yes: `APPLY -> ASSIGN -> RESOLVE`, four consecutive runs at switchover and every run since, with the
whole transition history in the v3 store (DB rows in section 2). Both process searches are
byte-identical to the 2.x baseline.

**What that proves is exactly one complaint at a time, driven by one employee who holds every role.**
Everything below is a thing the lifecycle cannot see.

`EGOV_WORKFLOW_HOST` serves **three paths** (`application.properties:93-95`), one of which carries
**three different query shapes** — five call shapes in all:

| 2.x call | CCRS caller | v3 |
|---|---|---|
| `POST .../process/_transition` | `WorkflowService:244` | `POST /workflow/v3/transition` |
| `POST .../businessservice/_search?businessServices=` | `WorkflowService:95`, `MigrationUtils:147` | `GET /workflow/v3/process/definition?processCode=` |
| `POST .../process/_search?businessIds=<csv>` | `WorkflowService.enrichWorkflow:131` | `GET /workflow/v3/transition?entityId=` |
| `POST .../process/_search?businessIds=<one>&history=true` | `NotificationService.java:663`, `EscalationService.java:278` | `...&history=true` |
| `POST .../process/_search?businessService=&assignee=` | `WorkflowService:264` | `...&assignee=` |

**One headline needs correcting here.** It has been said that one of these five appears in no source
file and was visible only in a proxy access log. That is not right, and this report will not repeat
it. The `history=true` shape was *missing from the task brief's inventory* and was found by reading
the `egov-workflow-proxy` access log during a green 2.x run — but it is plainly in the source, at
`NotificationService.java:663` and `EscalationService.java:278`. The access log remains the better
instrument (it is how the shape was found at all): 3000 lines of it show four of the five shapes in
live use, including 48 calls carrying `history=true`. The lesson is *watch the wire, then confirm in
the code* — not that the code was silent.

Had `history=true` been served as an ordinary search, `NotificationService.getEmployeeName` would have
scanned a one-row "history" for the last `ASSIGN`, found none, and notified nobody — its caller
catches and logs.

### 5.2 What was faked, dropped or worked around

The 259 differing facts against the baseline are one fact repeated: **2.x staples the whole state
DEFINITION and the whole assignee/assigner USER onto every transition reply; v3 returns a state CODE
and a user-id STRING.**

That is not an oversight in v3 — it is a different architecture. In 2.x the workflow service resolves
users itself: `egov-workflow-v2` (172.19.0.30) accounts for 179 of the `/user/_search` calls on the
user proxy. v3's workflow service does not call anyone. Rebuilding those fields would take a second v3
call per transition, which a one-request-in / one-request-out translator cannot express.

| field | action | what happened | to build |
|---|---|---|---|
| `state.actions[]`, `assignes[].roles`, `assigner.*` | dropped (193 facts missing) | v3 returns codes and id strings | a join, in v3 or in the caller |
| `moduleName` | dropped (37x) | 2.x overwrites the caller's value with `BusinessService.business`; v3 has neither field | persist the owning module |
| `processCode` | defaulted (32x) | 2.x businessIds search carries no business service; v3 makes it mandatory (400 without it). The shim substitutes `PGR`. Correct only because pgr-services is the sole caller — a second module behind this shim would be searched under the wrong process and answered empty with HTTP 200 (L116) | allow a search keyed on `entityId` alone |
| `Authorization` | faked (106x) | v3 enforces per-action roles and reads them **only** from `Authorization: Bearer` `realm_access.roles`. The `X-Roles` header its own constants name is ignored by this build (`X-Roles` -> 403, Bearer -> 200) and the signature is not checked, so `{"alg":"none"}` is honoured. The shim forges an unsigned JWT carrying the roles the inbound 2.x `RequestInfo` already asserted (L108) | a non-JWT role channel for trusted internal callers, a verified signature, and `roles` declared in `ActionInput` — it is **enforced but undocumented** |
| `isTerminateState` | faked/dropped (37x, L107) | see 5.3 | persist state type |
| `nextActions` | dropped (L113) | 2.x returns the role-filtered actions available next; v3 returns none on any runtime endpoint | return available actions with each instance |
| `totalCount` | dropped (instrumented) | v3 pages every list endpoint and caps `size` at 100; the 2.x envelope has no paging field, so a short list is indistinguishable from a complete one. The shim compares v3's own `totalCount` to the rows that arrived and logs the shortfall rather than padding or paging | an unbounded internal fetch, or paging in the 2.x callers |
| stranded `INITIAL` instance | faked (L119) | v3 creates the instance and *then* applies the action; a refused first transition leaves an orphan parked at `START`, a state no 2.x `applicationStatus` maps to. 2.x creates nothing on failure | roll back the instance when the creating transition fails |
| `additionalDetails` | dropped (L111) | see 5.3 | persist it |

### 5.3 Unservable — with storage-level proof

These are not gaps in the shim. They are things DIGIT 3.0 cannot represent.

**COMMENT and ESCALATE are dead.** CCRS models both as self-loops (`nextState == currentState`). v3
**stores them without complaint and then refuses them at runtime** with
`409 invalid state transition: no transition`. The stored rows are in `d3-postgres`:

```
name      current_state          next_state             roles
COMMENT   PENDINGATLME           PENDINGATLME           ["CITIZEN"]
COMMENT   PENDINGFORASSIGNMENT   PENDINGFORASSIGNMENT   ["CITIZEN"]
COMMENT   PENDINGFORREASSIGNMENT PENDINGFORREASSIGNMENT ["CITIZEN"]
COMMENT   REJECTED               REJECTED               ["CITIZEN"]
COMMENT   RESOLVED               RESOLVED               ["CITIZEN"]
ESCALATE  PENDINGATLME           PENDINGATLME           ["GRO","PGR_LME","PGR_VIEWER","SYSTEM"]
```

Six actions accepted at registration, all six unusable. Citizen commenting has nowhere to live — v3
carries a `comment` string on a *real* transition, so comments attached to a state change survive, but
the standalone comment does not. **To build:** self-loop transitions that record a row without moving
state, or a first-class "annotate without transitioning" call. Neither exists in `workflow.yaml`.

ESCALATE is dead twice over: even if self-loops worked, CCRS escalation **re-targets** the complaint to
the next supervisor up the HRMS `reportingTo` chain, and v3 escalation only fires an action — it cannot
choose who the work goes to, there is no org-hierarchy model anywhere in v3, and no HRMS in the v3
stack at all (L105).

**`isTerminateState` is unrepresentable.** The gap log says v3 accepts `TERMINAL_SUCCESS` /
`TERMINAL_FAILURE` and silently rewrites them to `INTERMEDIATE`. The schema shows why: the v3 `states`
table has **no state-type column at all** —

```
states: id, tenant_id, process_id, code, name, description, sla,
        is_initial, is_parallel, is_join, branch_states, createdBy, ...
```

`is_initial` is the only state-kind flag, and for PGR only `START` carries it. Nothing in v3 can tell a
caller that a complaint is closed. CCRS gets that from `applicationStatus`, so the lifecycle is
unaffected — but a v3-native consumer of this process definition cannot compute completion.

**`additionalDetails` is accepted and discarded.** `ProcessDefinitionInput` and `StateInput` both
declare it as free-form metadata; the `processes` table has no column for it, and reading a registered
definition back shows it absent at both levels. This matters more than the individual fields it was
carrying: it is the escape hatch the spec advertises for every 2.x concept v3 lacks, and it does not
exist. A shim cannot round-trip *anything* through it.

**The employee inbox is dead for >=2 complaints.** `WorkflowService.enrichWorkflow` joins every
complaint in a search result to its workflow in **one** call, `?businessIds=<csv>`, and throws
`WORKFLOW_NOT_FOUND` unless the counts match. v3's `GET /workflow/v3/transition` serves exactly one
`entityId`: the comma form returns `totalCount 0`, and the repeated form honours only the first value.
Measured A/B with `baseline/inbox-check.sh`, same box, same minute, only `EGOV_WORKFLOW_HOST` changed:

| workflow host | result |
|---|---|
| `egov-workflow-proxy:8109` (2.x) | HTTP 200, 2 wrappers — `runs/inbox-2x.json` |
| `hybrid-shim:9100` (v3) | HTTP 400 `WORKFLOW_NOT_FOUND`, 0 wrappers — `runs/inbox-v3.json` |

The lifecycle stays green throughout, because both of its searches are scoped to a single
`serviceRequestId`. **This is the single most important result in the report**: the happy-path test
cannot see the failure, and a purpose-built instrument had to be written to find it. Any real inbox
fails. **To build:** a multi-valued `entityId` filter or a bulk transition search.

**Batch transitions** (2.x takes a list; v3 takes one entity) and **workflow state migration** complete
the list. On state: nothing migrates process instances between the stacks. Tenant `pg` now has 96
instances in the 2.x store and 42 in v3, and **no tenant-wide employee search can succeed on either
engine** — which is how the inbox script's first version failed identically on both. **Flipping
`EGOV_WORKFLOW_HOST` is not reversible for complaints already in flight.**

Finally, a cleanup trap found while retiring probe definitions: **a v3 process definition cannot be
deleted once any instance references it** (`409 Record cannot be modified because it is referenced by
other resources`), and **process instances cannot be deleted at all** — the service exposes
`POST`/`GET /workflow/v3/transition` and nothing else. Two inert `ZZPROBE` definitions are therefore
permanent residents of the v3 store (`processes` = `ZZPROBE1, ZZPROBE2, PGR`). A mistaken or superseded
workflow can be orphaned but never retired.

### 5.4 Unimplemented, by contrast

Action `roles` undocumented in `ActionInput` though enforced; JWT signature unverified; `nextActions`
not returned; unknown-parameter handling; the `size <= 100` cap (reasonable in itself — it is the 2.x
callers that assume unbounded results). These are fixable in v3 without changing its model.

---

## 6. user / individual — **reverted; identity cannot move piecemeal**

### 6.1 Did the lifecycle pass

No. With `EGOV_USER_HOST` pointed at the shim:

```
PASS citizen_auth
FAIL create 400  {"Errors":[{"code":"INVALID_ACCOUNTID","message":"No user exist for the given accountId"}]}
```

(`runs/user-0451.json`.) The variable has been reverted and must stay reverted.

### 6.2 Why — the shape of the failure

`EGOV_USER_HOST` moves **three** pgr-services paths (`application.properties:75-78`): `/user/_search`,
`/user/users/_createnovalidate`, `/user/users/_updatenovalidate`. It does **not** move authentication.
The `egov-user-proxy` access log, grouped by source container IP (fresh sample taken today):

| path | source | moves with the variable? |
|---|---|---|
| `POST /user/_search` | pgr-services `.38` (559) | **yes** |
| `POST /user/users/_updatenovalidate` | pgr-services `.38` (1) | yes, but unservable |
| `POST /user/_details?access_token=` | **kong-gateway `.35` (205)** | **NO** |
| `POST /user/oauth/token` | **kong-gateway `.35` (120)** | **NO** |
| `POST /user/_search` | egov-workflow-v2 `.30` (179) | no — a different service's config |
| `POST /user/v1/_search` | egov-hrms `.36` (141) | no |

The gateway validates every inbound session against `egov-user` through **its own upstream**. So after
the flip CCRS keeps logging in, keeps minting 2.x `egov-user` uuids, stamps one onto each complaint as
`accountId` (`EnrichmentService:64`), and then asks the v3 individual registry about a uuid it has
never heard of (`UserService.enrichUser:110`) -> `INVALID_ACCOUNTID`.

**And v3 cannot be taught that uuid.** All three mappings were tried against the live service:

| attempt | what the deployed service does |
|---|---|
| create the individual **at** the 2.x uuid | the caller-supplied `id` is **silently ignored**: sent `aaaaaaaa-...`, got `629b9122-bc18-48d7-80fc-26731041e581`, HTTP 201. Same for `individualId` |
| store it in `individual.userId` | stored — but the `?userId=` filter is **accepted and ignored**: a value no individual carries returned **every** individual in the tenant, HTTP 200 |
| store it in `additionalAttributes` | accepted, but not a search filter — write-only |

That second row is the most dangerous behaviour found in either v3 service. `?userId=`, `?givenName=`
and even `?wibble=` all return the whole tenant with HTTP 200, while `individual.yaml` documents those
filters as real *and* promises that unknown parameters return 400. Neither statement is true of this
build. A shim that trusted the spec would ask for one citizen and hand CCRS a different one, silently.
The translator therefore sends only the three filters it has watched actually filter (`id`,
`individualId`, `mobileNumber`) and throws on everything else — some legitimate 2.x searches now fail
loudly rather than answering wrongly.

### 6.3 What was faked, dropped or defaulted

| CCRS concept | v3 | to build |
|---|---|---|
| `roles[]`, `type`, `userName` | **rejected as unknown fields on create.** A citizen created through v3 exists as a person and nothing else — no login, no authorisation, no user type | Keycloak realm roles + a 2.x-role -> realm-role map + a call per request |
| `User.id` (Long) | no numeric key in a uuid registry; emitted `null` rather than invented | nothing — uuids are the better design |
| `createdDate` | load-bearing: `UserUtils.parseResponse:79` parses it with no null check, so omitting it is an NPE inside pgr-services. Formatted from `auditDetail.createdTime` in UTC, substituted as *now* when v3 reports no audit block | nothing (2.x fragility) — but note the timezone shift |
| `gender` | **mandatory** in the deployed service (400 without it) though `individual.yaml` says optional; CCRS has no such field -> invented `OTHER` | collect it at registration (a CCRS UI change) or relax v3 |
| `givenName` | `^[A-Za-z ]+$` by default — an initial, a dot, a digit or a hyphen is rejected; 2.x accepts any string | per-tenant `nameRegex` via `POST /individual/v3/configs` |
| one `name` -> `givenName`/`familyName` | split on the first space — a guess, lossy on the round trip | a name model CCRS can populate |
| uniqueness on mobile | no `uniquenessCriteria` configured, so v3 stores duplicate mobiles while CCRS treats mobile as identity (`upsertUser` takes `.get(0)`) | configure it |
| `_updatenovalidate` | **unservable.** v3 `PUT` is optimistic-concurrency: no `version` -> `409 ROW_VERSION_MISMATCH`. Learning the version needs a second v3 call | an update accepting "latest", or an optional `If-Match` |
| page size | capped at 100; the translator contract cannot page, so it throws rather than truncate | larger page or cursor paging |

One deployment finding worth its own line: every create failed with `500 ENCRYPTION_ERROR` because
**Vault's transit engine had never been mounted** (`no handler for route transit/encrypt/IndividualEncrypt`).
The individual registry passed every health and readiness check, answered searches with HTTP 200, and
was incapable of storing a single person. Nothing in the API surface reveals that until the first
write.

### 6.4 The corrected roles claim, and the "NA" consequence

**Shim-supplied roles cannot reach a privilege decision — structurally, not merely in practice.**
`UserDetailResponse.user` is typed `List<org.egov.pgr.web.models.User>` (`UserDetailResponse.java:9`),
and `UserService.getFirstRoleNameByUuid:241` reads roles off exactly that list — the shim's own answer.
Every authorization site imports a **different** class, `org.egov.common.contract.request.User`, and
reads `requestInfo.getUserInfo()`, which the gateway fills from the 2.x token:
`RoleCodes.java:5,29`; `Principals.java:5,55`; `EmployeeContextService.java:8,69/154`;
`PolicyInputBuilder.java:5,48`; `PGRService.hasAnyRole` / `isAuthorizedForConfidential` (~437-442 / ~530).

The one real consumer of shim-supplied roles is
`ComplaintDomainEventService.getAssigneeName:226` — a **display string** on an outbound domain event,
null-tolerant on both hops. So the observable blast radius is precise and small:

> **Once `EGOV_USER_HOST` points at v3, every complaint domain event will name its assignee as the
> literal string `"NA"`.** v3's Individual carries no roles, the shim will not invent any,
> `getFirstRoleNameByUuid` returns null for an empty list, and `getAssigneeName` falls back to `"NA"`.

Nothing errors, nothing is logged, nothing fails a test. The field is also misleadingly named — it
holds a *role* name ("Grievance Routing Officer"), not a person. This is exactly the kind of thing that
surfaces six months into a migration as *"why do all our notifications say NA"*.

### 6.5 Unservable vs unimplemented

- **Unservable:** the identity move itself. Not a translation problem at any effort. A 2.x opaque
  access token has no representation in DIGIT 3.0, which expects a Keycloak-issued JWT; and even with
  token translation, the uuid mapping cannot be constructed statelessly. `_updatenovalidate` is
  unservable for a different reason: it needs a read before the write.
- **Unimplemented:** ignored filters (spec promises 400), mandatory `gender`, `nameRegex`,
  `uniquenessCriteria`, unmounted Vault transit, page cap. All fixable.
- **Genuinely better in v3:** the repeated `?id=a&id=b` form really does OR, so `searchBulkUser` — the
  multi-complaint inbox that is dead on the workflow service — *is* servable on this dependency.

**A real migration must:** (a) migrate every `egov-user` row into the individual registry; (b) provide a
durable `2.x-uuid -> individual-id` map, or an individual create that honours a caller-supplied id; and
(c) issue sessions from an identity provider both halves trust. DIGIT 3.0 already names it —
`d3-keycloak` is running and `d3-individual` carries `KEYCLOAK_ENABLED=true`. CCRS's login does not use
it. **Identity is the first thing to move, not the fourth.**

---

## 7. The dependencies that were never switched

| dependency | v3 counterpart | status | classification |
|---|---|---|---|
| **localization** (`EGOV_LOCALIZATION_HOST`) | **none** — no localization service in the v3 stack, and none in `deploy/local/docker-compose.yml` | untouched, out of scope by the plan | **unimplemented in this deployment**; nothing was proven either way |
| **HRMS** (`EGOV_HRMS_HOST`) | **none** — no HRMS, and no org-hierarchy model anywhere in v3 | untouched | **unservable today.** Employee jurisdiction and the `reportingTo` chain that CCRS escalation rides on have no v3 representation (5.3). Note `egov-hrms` is also a *second* consumer of `EGOV_BOUNDARY_HOST` and of `/user/v1/_search`, so it moves independently |
| **MDMS** (`EGOV_MDMS_HOST`) | `d3-mdms-v2` runs; `eg_mdms_data` exists in the v3 DB | untouched, untested | **unimplemented / unproven.** CCRS uses MDMS **v1** search (`/egov-mdms-service/v1/_search`); v3 ships v2. Plausibly translatable, entirely unmeasured. MDMS is where the id format and the whole `RAINMAKER-PGR.ComplaintHierarchy` live — it is not a small dependency |
| **enc-service** (`EGOV_ENC_HOST=http://egov-enc-service:1234`) | **none** — v3 encrypts inside the individual service via Vault transit | untouched | **not a re-pointable dependency.** Different architecture, not a missing endpoint |
| **url-shortening** (`EGOV_URL_SHORTNER_HOST`) | **none** | untouched | **unimplemented in this deployment** |
| **all Kafka paths** | **none — the v3 stack has no broker at all** | untouched | **unservable by this method, by construction** |

The Kafka row deserves prose, because it is the largest unexamined surface in the whole exercise.

CCRS is not only an HTTP client. `digit-pgr-services-1` carries
`SPRING_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092`, `SPRING_KAFKA_CONSUMER_GROUP_ID=egov-pgr-services`,
`KAFKA_TOPICS_COMPLAINTS_DOMAIN_EVENTS=complaints.domain.events` and
`EGOV_USR_EVENTS_CREATE_TOPIC=persist-user-events-async`, and complaints reach Postgres through
`egov-persister` consuming those topics — not through a REST call to a service. **None of that can be
re-pointed by an environment variable, because none of it is HTTP.** The only broker on the box is
`digit-redpanda`, part of the 2.x project; the v3 compose file defines no broker service. DIGIT 3.0 as
deployed here is synchronous REST plus its own Postgres.

So the shim methodology — flip a host variable, translate a request, compare a run — has a hard
boundary: **it can only ever measure the HTTP half of CCRS.** Persistence, indexing, notification
dispatch and domain events were not tested, and are not testable this way. Anyone reading "three of
four dependencies are green" should read it as "three of four *HTTP* dependencies", with the async half
untouched.

One related structural fact: DIGIT 3.0 ships its own PGR service (`pgr:` / `d3-pgr` at
`deploy/local/docker-compose.yml:709`), and it is **not running** on this box. The realistic migration
question for Bomet/Nairobi may not be "can CCRS be re-pointed at v3 services" but "should CCRS's
grievance model move onto v3's PGR". This exercise answers the first question and says nothing about
the second.

---

## 8. Unservable vs unimplemented — the consolidated list

The distinction: **unimplemented** means the deployed v3 build does not do something its own spec,
schema or config says it should — a bug, a seed or a config away from working. **Unservable** means no
translator, at any effort, can bridge it without changing CCRS or DIGIT 3.0 itself.

### Unservable

1. **Identity as a whole.** Authentication does not travel with `EGOV_USER_HOST`; v3 mints its own ids
   and ignores a caller-supplied one; a 2.x opaque token has no v3 representation.
2. **Self-loop actions — COMMENT and ESCALATE.** Stored, then refused at runtime.
3. **Escalation up the `reportingTo` chain.** No org model in v3, no HRMS in the stack.
4. **Multi-id workflow search** — and therefore every employee inbox holding >=2 complaints.
5. **Batch transitions.** One entity per v3 call; a partial batch is worse than none.
6. **`isTerminateState`.** No storage for it; `is_initial` is the only state-kind flag.
7. **`_updatenovalidate`.** Optimistic concurrency needs a read before the write.
8. **All Kafka paths** — persister, domain events, notification consumers. Not HTTP.
9. **Workflow state migration**, and with it the reversibility of the workflow flip.
10. **The 2.x shared named ID sequence.** v3's `{SEQ}` is per-template and cannot bind to it.

### Unimplemented (fix, seed or configure)

1. Multi-code boundary search (spec says array; build reads one code).
2. Query filters accepted and silently ignored, answering 200 with the whole tenant (spec promises 400).
3. `additionalDetails` accepted and discarded on definitions and states.
4. Action `roles` enforced but undocumented, readable only from an unsigned, unverified Bearer JWT.
5. `nextActions` not returned on any runtime endpoint.
6. Process definitions undeletable once referenced; process instances undeletable, period.
7. A refused first transition leaves a stranded `INITIAL` instance.
8. Boundary geography and individual records not migrated (seeding).
9. Vault transit engine unmounted — a registry that is healthy and cannot store a person.
10. `gender` mandatory, `nameRegex` too strict, `uniquenessCriteria` unset (tenant config).
11. Template `start` changeable only by delete-and-recreate.
12. Page caps with no internal unbounded fetch.

---

## 9. What this exercise does NOT establish

The shim is a **throwaway proof**, and the following are not claims it can support:

- **No load or concurrency testing.** Every measurement is one request at a time. Nothing is known
  about v3 under contention, about the 100-row page cap at real inbox sizes, or about the shim's own
  behaviour beyond a single caller.
- **One tenant: `pg`.** Not `mz`, the state-level tenant the plan named — `mz` is half-onboarded (no
  `ComplaintHierarchy`, no boundaries, no citizen) and cannot complete a lifecycle even on unmodified
  CCRS. Nothing multi-tenant was exercised, and tenancy moves from a body field to a header in v3.
- **One complaint type** (`BurningOfGarbage`), **one locality** (`SUN01_LOCALITY`), **one citizen**,
  **one employee** who happens to hold GRO + PGR_LME + CSR simultaneously. Role separation between
  assigner and resolver was never tested.
- **53 complaints in the whole database.** This is a toy dataset.
- **The UI was never exercised.** `digit-ui` is running and was never pointed at any of this. Every
  result is API-level.
- **Citizen authentication is not actually authenticating** — `pg` returns 200 for any password
  (OTP mode, enforcement off). The lifecycle's first step proves less than it appears to.
- **Only the happy path, plus one purpose-built negative instrument.** `inbox-check.sh` found a fatal
  defect the lifecycle could not see. There is no reason to believe it is the only one; REOPEN, RATE,
  REJECT, REASSIGN and the escalation scheduler were never driven end to end.
- **The async half of CCRS is untouched** (section 7).
- **The shim itself is not a migration path.** It is stateless, un-authenticated, un-cached, has no
  retries, holds one request in and one out, and its 2.x envelopes are reconstructions matched against
  live 2.x replies — not contract guarantees.
- **`compare.sh` cannot see everything.** Its ignore list deliberately drops volatile paths including
  the `serviceRequestId` value, so a green comparison is not evidence that the id is well-formed; that
  was checked by hand against the database.

---

## 10. Recommendations for a real migration

1. **Move identity first.** Everything else is downstream of it, and it is the one dependency no shim
   can help with. Concretely: migrate `egov-user` into the individual registry, put CCRS login on
   Keycloak, and keep a durable `2.x-uuid -> individual-id` map for the complaints already carrying an
   `accountId`.
2. **Take idgen and boundary now if you want early wins** — they are ready, and their remaining work is
   data migration plus four key renames. Freeze the 2.x sequence when you cut idgen over, and retire
   the gatus check that keeps it moving.
3. **Do not move workflow until v3 supports self-loop transitions, multi-entity search and state
   type.** Until then the flip costs citizen comments, escalation and every multi-complaint inbox, and
   cannot be undone for in-flight complaints.
4. **Count the endpoints behind every host variable before flipping it**, and write a negative test for
   the call shape the happy path does not exercise. One env var moved two boundary APIs; the one that
   was missed returned zero complaints with HTTP 200.
5. **Treat "accepted and ignored" as the dominant failure mode of this v3 build.** A caller-supplied
   `id`, a `userId` filter, `additionalDetails`, a state `type`, a `count` on the non-bulk idgen path —
   all accepted, all discarded, all with a success status. Probe the live service; the specs were wrong
   on every one of these.
6. **Ask whether CCRS should be re-pointed at v3 services at all, or moved onto v3's own PGR service**
   (`d3-pgr`, defined and not running here). This exercise answers only the first question.

---

## Appendix — reproducing any claim here

```bash
# on egov-subhashini
cd /opt/hybrid-shim
node --test test/                                    # 109/109
OUT=runs/<name>.json bash baseline/lifecycle.sh      # the complaint lifecycle
bash baseline/compare.sh baseline/baseline-20260920-0238.json runs/<name>.json
OUT=runs/<name>.json bash baseline/inbox-check.sh    # the >=2-complaint inbox: green on 2.x, red on v3
python3 flip_{idgen,boundary,workflow,user}.py orig  # rollback, then restart pgr-services and
docker restart digit-pgr-services-1                  #   clear the 30-minute boundary cache
```

Gap log: grep the field name, sort by `ts`, **take the last entry** (section 2). Rollback compose files
are kept per task at `/opt/hybrid-shim/docker-compose.egov-digit.yaml.task{3,4,5,6}-orig`.
**egov-bomet (62.238.3.196) was never touched by any part of this work.**
