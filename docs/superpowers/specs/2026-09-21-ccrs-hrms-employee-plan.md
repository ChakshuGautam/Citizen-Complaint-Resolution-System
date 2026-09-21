# CCRS HRMS → employee migration, 2.12 → 3.0: a costed work breakdown

**Date:** 2026-09-21 · **Audience:** whoever decides to fund this and when to schedule it
**Scope:** employees, assignments, jurisdictions, the supervisor edge, and the HRMS Kafka
projection. Not citizens, not authentication, not complaints — those are
`2026-09-21-ccrs-identity-migration-plan.md` and are **not re-costed here**.
**Evidence:** every number was counted today against the live lab (egov-subhashini, 2.x and
v3 databases, read-only except for the POC's own seeds) and the repo on egov-server, branch
`develop`. Where a number cannot be grounded this document says **"not estimable without X"**.

**Prior analysis this builds on and does not repeat:**
`2026-09-20-ccrs-digit3-migration-requirements.md` (W10, W15, W21), `…-hybrid-findings.md` §6,
and `2026-09-21-ccrs-identity-migration-plan.md` (A2, A3, B1, B2, C1, C2, D1, D2).
**Task numbering continues that plan's scheme with an `H` prefix**, and every `H` task names
the identity task it depends on rather than re-costing it.

---

## Headline

| | days |
|---|---|
| **CCRS-side, schedulable after the identity plan's A2/B2/C1/C2** | **optimistic 31 · likely 47 · pessimistic 92** |
| **Blocked on DIGIT** | **2 items, one of them the whole escalation feature** |

**Critical path (34 of the 47 likely days):** H1 employee map → H2 ETL → H4 the supervisor
edge (the fork) → H6 projection replacement → H7 read-site rewrite.

**The single decision that moves this number is H4.** Escalation and team-inbox visibility
both walk the HRMS `reportingTo` chain, and **DIGIT 3.0 has no such field anywhere**. The three
options cost **3 days (if DIGIT ships it), 12 days (if CCRS owns the edge), or "cannot be done"
(if you hope v3's jurisdiction model approximates it)**. §4 argues the third is not an option.

**The dangerous failure is silent.** `VisibilityService:107-112` treats "no reportees" as
"show the tenant-wide view", not as an error. Migrate with an empty supervisor edge and every
supervisor's TEAM inbox silently becomes *everyone's* inbox. Nothing logs, nothing 500s, no
test goes red.

---

## 0. What is actually there — counted today

**2.x HRMS (`egov` database, lab):**

| table | rows | note |
|---|---|---|
| `eg_hrms_employee` | **7** | `pg`=4, `pg.citest`=1, `mz`=1, `mz.maputo`=1 |
| `eg_hrms_assignment` | **14** | **7** current, **2** carry a `reportingto` |
| `eg_hrms_jurisdiction` | **7** | 2 hierarchies, 3 boundary types, 5 boundaries |
| `eg_hrms_servicehistory` / `educationaldetails` / `departmentaltests` / `empdocuments` / `deactivationdetails` / `reactivationdetails` | **0 each** | six tables, no rows — **do not cost an ETL for them at lab volume, and do not assume 0 at Bomet** |
| distinct departments / designations / positions | **6 / 3 / 10** | MDMS codes, free text in both schemas |
| `eg_pgr_hrms_projection` (CCRS's own) | **6** | one chain `PGGRO1 → PGLME2 → PGLME3`; 3 roots |
| `eg_user` where `type='EMPLOYEE'` | **17** | **only 7 have an HRMS record.** 10 employees exist as logins with no employment record at all |
| `eg_userrole_v1` grants to employees | **118** of 121 | costed as identity-plan C2, **not here** |

**v3 (`d3-postgres`, lab):** `employee_v3` **0 rows before this POC, 4 after**;
`employee_jurisdiction_v3` **0**; `access_rbac_rules` **0**; `access_jbac_rules` **0**;
Keycloak `user_entity` **1** (`admin`, realm `master`), **0 tenant realms**.

**The 17-vs-7 gap is the first thing to decide and it is not a technical question.** Ten
people can log in to CCRS today and have no HRMS record; v3's employee registry has no way
to represent them, and CCRS's department validation (`ServiceRequestValidator:183`) already
throws `DEPARTMENT_NOT_FOUND` for them. They are either dormant accounts to be retired, or
a gap in the 2.x data. **A1 of the identity plan should count this at Bomet too.**

---

## 1. The task list

Each task states: what it is · what it touches · what it depends on · what "done" means as an
observable test · days, with the basis.

---

### H1 · The durable `2.x uuid → v3 employee id` map — **2 days**

*What:* a second mapping table of the same shape as identity-plan **A2**, pointing at
`employee_v3` instead of `individual_v3`. Proven in the POC as
`hybrid/src/empmap.js` (173 lines, JSON file).

*Touches:* one new Flyway migration + a DAO, alongside A2's.

*Depends on:* identity-plan **A2** (share its table conventions and its reconciliation
instrument A3). Schedulable in the same sprint.

*Done:* the table exists with a unique constraint on the source uuid **and** on
`employee_id`; seeding the lab's 7 HRMS employees writes 7 rows; **a second run changes 0 rows.**

*Basis:* A2 is costed at 2 days for the same shape and the precedent now exists; this is the
second instance, not the first. **It is a separate map, not a `kind` column on A2** — the POC
made that call because A2 enforces "no two 2.x uuids share one individual", an invariant that
says nothing about employees, and merging them means weakening it.

**This map is mandatory for the same reason A2 is, plus one more:** `employee_v3.user_id`
already holds the 2.x uuid, but `GET /employee/v3/employees?userId=` is **accepted and ignored**
— verified live, it returned every employee in the tenant with HTTP 200. The registry holds
the answer and cannot be asked the question.

---

### H2 · ETL: `eg_hrms_employee` + assignments + jurisdictions → `employee_v3` — **6 days**

*Touches:* new ETL; writes `employee_v3`, `employee_jurisdiction_v3`, H1's map.

*Depends on:* H1, identity-plan **B2** (tenant realms) and **C1** (individuals) — see the
blocker below, which is not optional.

*Volume:* 7 employees, 14 assignments (7 current), 7 jurisdictions, 6 departments,
3 designations, 10 positions.

**Three measured v3 behaviours that cost the time, none of which is row count:**

- **An employee cannot be created without a Keycloak user in a realm named after its tenant.**
  `POST /employee/v3/employees` validates `userID` against Keycloak, and the deployed
  `d3-employee` binary reads `KEYCLOAK_BASE_URL` — **unset in this stack's compose file** —
  falling back to its compiled-in default `https://digit-lts.digit.org/keycloak`, DIGIT's own
  LTS demo environment, asking for realm `PG` (the tenant code, upper-cased):
  ```
  failed to validate user ID: keycloak service request failed:
  Get "https://digit-lts.digit.org/keycloak/admin/realms/PG/users/<id>":
  dial tcp: lookup digit-lts.digit.org: no such host
  ```
  **H2 cannot start before identity-plan B2 provisions the realms and C1 creates the users.**
  The POC worked around it with direct SQL (`hybrid/seed-employees.sh`) and says so.
- **v3's Kong has no `/employee` route.** 10 routes exist, none of them employee. Adding it
  is hours, but it is a deployment change nobody has made and it is not in the compose file.
- **The employee model is flat.** `employee_v3` is `(id, code, user_id, individual_id, status,
  employee_type, date_of_appointment, department, designation, is_active, tenant_id)`. There is
  **one** department and **one** designation per employee — not a list of assignments with
  dates. The lab's 14 assignments collapse to 7 rows and **7 historical assignments are
  dropped**, including `position`, `govtordernumber`, `ishod`, `fromdate`/`todate`. That is a
  data-loss decision, not a mapping detail, and it needs signing off.

*Done:* 7 `employee_v3` rows and 7 `employee_jurisdiction_v3` rows exist; H1's map holds 7;
A3 reports PASS; **re-running the ETL changes 0 rows**; and a named report lists every dropped
assignment field with the row count for each.

*Basis:* 3 target tables · 2 source joins · the assignment-collapse decision and its report ·
idempotency against a registry that mints its own ids. **Does not scale from here by row
count — Bomet is unmeasured (identity-plan A1).**

---

### H3 · What CCRS reads from HRMS, field by field — **3 days to close the gaps that can be closed**

*Touches:* `HRMSUtil.java` (118), `NotificationService.java` (5 HRMS sites),
`ServiceRequestValidator.java` (1), `EmployeeContextService.java` (242),
`HrmsProjectionService.java` (160), `HrmsScopeSemantics.java` (20). **19 references across
5 Java files** for the live-HRMS path, plus the projection stack.

**The complete list of fields CCRS consumes, and whether v3 can supply each:**

| # | field CCRS reads | JSONPath / accessor | read by | v3 `employee_v3` can supply? |
|---|---|---|---|---|
| 1 | `Employees[*].assignments[*].department` | `HRMS_DEPARTMENT_JSONPATH` | `ServiceRequestValidator:183` (assignee validation), `NotificationService:724` | **Yes**, as a single column — current assignment only |
| 2 | `Employees[*].assignments[?(@.department==X)].designation` | `HRMS_DESIGNATION_JSONPATH` | `NotificationService:739` | **Yes**, single column — but the *filter by department* is meaningless once there is one row |
| 3 | `Employees[*].user.name` | `HRMS_EMP_NAME_JSONPATH` | `NotificationService:743` | **No.** No name column. It is behind `individual_id`, in the other registry — a second call |
| 4 | `Employees[0].assignments[?(@.isCurrentAssignment==true)].reportingTo` | `HRMS_REPORTING_TO_JSONPATH` | `HRMSUtil.getSupervisorUuid` → `EscalationService` | **NO. No field exists.** §4 |
| 5 | `assignments[*].isCurrentAssignment` | `HrmsScopeSemantics.isCurrentAssignment` | projection + `EmployeeContextService:138` | **N/A** — collapsed away by H2 |
| 6 | `jurisdictions[*].{hierarchy,boundaryType,boundary}` | `EmployeeContextService.activeJurisdictions:187-211` | `/employee/context` (working-context API) | **Partly.** `employee_jurisdiction_v3.boundary_relation` is a jsonb blob; the three named fields must be re-derived from it. Shape unverified — **0 rows in the lab** |
| 7 | `jurisdictions[*].isActive` | `HrmsScopeSemantics.isActiveJurisdiction` | as above | **Yes**, `employee_jurisdiction_v3.is_active` |
| 8 | `isActive` (employee-level), falling back to `user.active` | `HrmsProjectionService:70` | projection | **Yes**, `is_active` — but the `user.active` fallback has no v3 counterpart |
| 9 | `uuid`, falling back to `user.uuid` | `HrmsProjectionService:60` | projection | **Via H1's map only.** `employee_v3.id` is a different id space |
| 10 | `tenantId` | `HrmsProjectionService:62` | projection | **No.** `GET /employee/v3/employees/{id}` does not echo the tenant; it is an inbound header only |
| 11 | `Employees` array + `limit`/`offset` paging | `HrmsProjectionService.rebuildProjection` | nightly rebuild | **Yes**, `?size=&page=`, capped at 100 |

**Verdict: 6 of 11 supplied, 2 partial, 3 not supplied at all** — the employee's **name** (#3),
the **tenant** (#10) and **`reportingTo`** (#4). #3 and #10 are solvable in the ETL (carry the
name via `individual_id`; carry the tenant in H1's map). **#4 is not solvable inside CCRS** and
is costed separately as H4.

*Done:* a test asserting each of the 8 supplyable fields round-trips from `employee_v3`
through the adapter to the exact shape the existing JSONPaths read — i.e. **the JSONPaths do
not change**, the payload feeding them does.

*Basis:* 11 fields · 19 call sites across 5 files · the two partials each need a decision.
This excludes #4 entirely.

---

### H4 · THE SUPERVISOR EDGE — **3 · 12 · or not possible.** See §4. **This is the decision.**

---

### H5 · The `/employee/context` working-context API — **4 days**

*Touches:* `EmployeeContextService.java` (242 lines), `EmployeeWorkingContext.java` (66),
`EmployeeContextServiceTest.java`.

*Depends on:* H2, H3.

*Today it reads three things:* departments from `assignments` (H3 #1), **roles from
`RequestInfo.userInfo.roles` filtered to the logged-in tenant** (`tenantRoles:170-181` —
that is identity-plan C2's problem, not HRMS's), and jurisdictions from `jurisdictions[]`
(H3 #6).

*The measured risk is the roles half.* `classifyRoles` maps role codes onto
RESOLVER / CITIZEN / ADMIN contexts, and `pgr.employee.context.admin-role-codes` defaults to
**7 codes** (`PGR_ADMIN, SUPERUSER, MDMS_ADMIN, HRMS_ADMIN, STADMIN, SUPERVISOR,
PGR_SUPERVISOR`). Under v3 these must arrive as Keycloak realm roles **stamped with the
tenant** — `tenantRoles` discards any role whose `tenantId` does not equal the logged-in
tenant, and a Keycloak realm role has no tenant field of its own. **If C2 lands roles
without a tenant stamp, this endpoint returns an empty role list and every employee silently
loses their admin context.** That is a one-line filter and a whole class of outage.

*Done:* the working-context response for each of the 7 employees is byte-identical to 2.x,
including `roleContexts`, proven per employee; and a negative test asserts that a role
without the logged-in tenant is still excluded.

*Basis:* 242 lines · 3 sources · the tenant-stamp interaction with C2 · an existing test class
to re-cut.

---

### H6 · Replace the Kafka employee projection — **7 days · BLOCKED ON A DECISION**

*Touches:* `HrmsProjectionConsumer.java` (64), `HrmsProjectionService.java` (160),
`HrmsProjectionRepository.java` (72), `V20260715000000__create_hrms_projection.sql`,
`configs/egov-persister/hrms-employee-persister.yml` (**533 lines, 19 queries**).

*Depends on:* H2, H4 (the projection's whole purpose is to store the supervisor edge).

**What exists.** CCRS keeps `eg_pgr_hrms_projection` — `(uuid, tenantid, reporting_to,
department, active, lastmodifiedtime)`, one row per employee, **6 rows in the lab** — fed by
**two independent paths**:

1. **Streaming.** `@KafkaListener(topics = {save-hrms-employee, update-hrms-employee})`.
2. **Sweep.** A nightly full rebuild (`0 45 2 * * *`) **plus** a boot-time backfill when the
   table is empty, paging `egov-hrms` at `limit=100`. This is the backstop for missed events.

Read by `VisibilityService:107` through a **recursive CTE** capped at a configured depth.

**v3 has no broker.** `d3-employee` runs with `PUBSUB_ENABLED=false`; the binary carries Kafka
and Redis pub/sub support and **neither is configured in this stack**. There are no
`save-hrms-employee` / `update-hrms-employee` topics to consume, and no v3 topic that means
the same thing.

**Three replacements, and the cheap one is already half-built:**

| option | what it is | days | the catch |
|---|---|---|---|
| **(a) Sweep only** | Delete the consumer. Keep the rebuild, raise its frequency. | **3** | The sweep **already exists and is already the backstop**, so this is deletion plus a cron change. The cost is *staleness*: an org change takes up to one rebuild interval to reach the inbox. Today it is ~instant. At 6 rows a 5-minute sweep is free; at Bomet's volume it is **not estimable without identity-plan A1** |
| **(b) Enable v3 pub/sub** | Turn on `PUBSUB_ENABLED` in `d3-employee`, consume its topics | **7, and it is not CCRS's 7** | Requires DIGIT to (i) support it in a supported deployment, (ii) document the event shape, (iii) guarantee an event per employee change. **None of the three is established.** The binary's support is not the same as a contract |
| **(c) Read-through, no projection** | Delete the table; call `d3-employee` live on each inbox load | **5** | Kills the design's stated goal — "**zero live HRMS calls**" (VISIBILITY-DESIGN §4.3). The recursive CTE becomes an N+1 walk over HTTP. At depth 3 with a team of 20 that is 20+ calls per inbox render |

**Recommendation: (a), and write the staleness into the tenant's config, not into a comment.**
It is the only one that is entirely in CCRS's hands, and the sweep it depends on is the
existing, tested backstop.

*Done:* the consumer class is deleted; `grep -c "save-hrms-employee" backend/` returns 0;
the projection reaches the same 6 rows from a cold start via the sweep alone; the recursive
CTE returns the same reportee set; and the rebuild interval is a per-tenant MDMS value with a
documented worst-case staleness.

*Basis for 3 days (option a):* delete 64 lines + 2 properties · re-point the 533-line
persister config (**19 queries** — most of them are HRMS's own persistence, not CCRS's, and
must be handed back to whoever owns `egov-hrms`) · make the interval configurable · prove
cold-start convergence.

---

### H7 · Point the 19 read sites at the new source — **5 days**

*Touches:* `HRMSUtil.java`, `NotificationService.java`, `ServiceRequestValidator.java`,
`EmployeeContextService.java`, `HrmsProjectionService.java`, `HrmsScopeSemantics.java`.

*Depends on:* H2, H3, H5, H6.

*The shape of the work:* CCRS reads HRMS through **JsonPath over a raw `Object`**, not through
a typed client. Four constants in `PGRConstants` (lines 49, 51, 53, 222) carry the paths, and
`getHRMSURI` builds `{host}/egov-hrms/employees/_search?tenantId=&uuids=`. The cheapest
correct change is an **adapter that answers in the 2.x `Employees[]` shape** — the JSONPaths
then do not move, which is worth a great deal given #4 is a `?(@.isCurrentAssignment==true)`
filter over an array that no longer exists.

*Done:* `egov.hrms.host` is unset and nothing calls it; all existing HRMS-touching tests pass
unmodified; `assign` on the lab produces a byte-identical notification payload to 2.x,
**including the employee name and designation**, proven per field.

*Basis:* 19 call sites · 4 JSONPath constants · one adapter · 11 existing test classes
reference HRMS and must stay green.

---

### H8 · Reconciliation for the employee half — **2 days**

*Touches:* extends identity-plan **A3**.

*Done:* one command prints, per tenant, `eg_hrms_employee` / `employee_v3` /
`employee_jurisdiction_v3` / `eg_pgr_hrms_projection` counts and PASS/FAIL on H1's map being
total and injective. **Acceptance is a negative test: run against the lab today it must report
FAIL** (7 HRMS employees, 4 `employee_v3` rows from the POC, 0 jurisdictions).

*Basis:* A3's machinery already exists; this is 4 more counts and one more invariant.

---

## 2. Totals

| task | optimistic | likely | pessimistic |
|---|---|---|---|
| H1 employee map | 1 | 2 | 4 |
| H2 ETL | 4 | 6 | 12 |
| H3 field-by-field gap closure | 2 | 3 | 6 |
| **H4 supervisor edge** | **3** | **12** | **30** |
| H5 working context | 3 | 4 | 8 |
| H6 projection replacement (option a) | 2 | 3 | 7 |
| H7 read-site rewrite | 4 | 5 | 10 |
| H8 reconciliation | 1 | 2 | 3 |
| **total** | **31** | **47** | **92** |

**Not estimable, and excluded from every column:**

- **Bomet/Nairobi volumes.** Identity-plan **A1** is the prerequisite. The one real-tenant
  number that exists is 31× the lab, and **nothing here scales linearly** except H2's row loop.
- **`employee_jurisdiction_v3.boundary_relation` shape.** 0 rows in the lab; the jsonb schema
  is unverified. H3 #6 and H5 both depend on it. **Not estimable without one real row.**
- **H4 option (a)** — a DIGIT roadmap item, not a CCRS estimate (§4).
- **H6 option (b)** — a DIGIT contract, not a CCRS estimate.
- **The 10 employees with no HRMS record.** A data decision, not an engineering one.

---

## 3. Dependency order

```
identity A2 ─┬─> H1 ─> H2 ─┬─> H3 ─> H5 ─┐
identity B2 ─┤             │             ├─> H7
identity C1 ─┘             ├─> H4 ─> H6 ─┘
identity C2 ──────────────> H5 (roles half)
                            H8 runs after every step
```

H1 is schedulable the moment A2's table conventions are fixed. **H2 cannot start until B2 and
C1 are done** — not a preference, a hard 400 from `d3-employee` (§H2). H4 should be *decided*
first and *built* whenever, because H6's design depends on the answer.

---

## 4. ESCALATION — the one that does not have a good answer

### What CCRS does today

```java
// PGRConstants:222
HRMS_REPORTING_TO_JSONPATH =
  "$.Employees[0].assignments[?(@.isCurrentAssignment==true)].reportingTo"
```

`HRMSUtil.getSupervisorUuid` reads it live from `egov-hrms`;
`EscalationService.prepareEscalation` resolves the current assignee's supervisor and
**refuses the transition** if there is none:

```java
if (expectedAssignee == null)
    throw new CustomException("ESCALATION_TOP_OF_HIERARCHY",
        "No reportingTo employee exists for the current assignee");
```

It also refuses a cycle (`ESCALATION_HIERARCHY_CYCLE`) and refuses any caller-supplied
assignee that is not the supervisor (`INVALID_ESCALATION_ASSIGNEE`) — i.e. **ESCALATE is
defined as "assign to my supervisor" and nothing else.** `EscalationScheduler` drives the
same code automatically.

**There is a second consumer of the same edge**, and it fails in the opposite direction:

| consumer | source | behaviour with no supervisor edge |
|---|---|---|
| `EscalationService` | live `egov-hrms` | **Hard 400**, `ESCALATION_TOP_OF_HIERARCHY`. Auto-escalation stops for that complaint. Loud. |
| `VisibilityService` → `HrmsProjectionRepository.getReporteeUuids` (recursive CTE) | `eg_pgr_hrms_projection` | **Silent widening.** `VisibilityService:107-112`: empty reportee set ⇒ TEAM inbox falls back to the **tenant-wide** view. Not an empty inbox — everyone's. |

Both are live on the lab today (`PGR_VISIBILITY_ENABLED=true`; `PGR_ESCALATION_ENABLED=false`,
so the *scheduler* is off but the manual path is not).

**And the 2.x data is thin.** Of **14** assignments, **2** carry a `reportingto`. The whole
ladder is `PGGRO1 → PGLME2 → PGLME3`; the other 5 employees are roots. Migrating this edge
means migrating **2 rows** at lab volume — and an unmeasured number at Bomet, where the same
`ComplaintHierarchy` count was 31× the lab.

### Option (a) — DIGIT adds `reportingTo` to the v3 employee model — **3 days of CCRS work, blocked**

*CCRS side:* one column in H1's ETL, one adapter field in H7, done. **3 days.**

*Why it is the right shape:* the supervisor edge is an attribute of employment. It belongs on
the employment record, in the same transaction that creates it, in the registry that already
owns `department` and `designation`.

*Why it is blocked:* `employee_v3` has no such column and `d3-employee` has no such field.
This is a schema change to a DIGIT service, on DIGIT's roadmap, on DIGIT's timeline.
**Not estimable, and it is not a CCRS estimate to make.** It is worth asking for explicitly,
because it is one nullable `character varying` and it unblocks two CCRS features.

### Option (b) — CCRS owns the supervisor edge itself — **12 days**

*Where it lives:* `eg_pgr_hrms_projection` **already is this table**. `(uuid, tenantid,
reporting_to, department, active, lastmodifiedtime)`, one row per employee, with the index
the reportee walk needs and a recursive CTE already written against it. Promote it from a
*projection* (derived, disposable, rebuildable) to a *system of record* (authoritative,
backed up, edited). The read side does not change at all.

*What writes it:*

1. **A one-shot ETL** from `eg_hrms_assignment.reportingto` at cutover — 2 rows today.
2. **An admin UI/API in CCRS**, because after cutover nothing else can. Today an HR change in
   `egov-hrms` propagates in seconds via the topics; afterwards, if CCRS owns the edge, **HR
   changes it in one system and CCRS does not find out.** This is the real cost of (b) and it
   is not the table.

*What breaks, named:*

- **The two systems drift, permanently and silently.** A promotion recorded in v3's employee
  registry does not move CCRS's supervisor edge. The only detector is a reconciliation
  report nobody reads, and the failure mode on the visibility side is the tenant-wide inbox.
- **`rebuildProjection` must be deleted, not kept.** It currently *overwrites* `reporting_to`
  from HRMS on every nightly sweep. Leave it in and the nightly job silently reverts every
  edit an admin makes. This is the single most likely way to ship (b) broken.
- **Authority becomes ambiguous.** CCRS starts asserting an organisational fact about a
  person. Who may edit it, who audits it, and what happens at an employee's deactivation are
  now CCRS's questions. `eg_hrms_deactivationdetails` has 0 rows today and this makes that
  table CCRS's problem.
- **It does not survive contact with a second module.** The moment anything else in the stack
  needs the org chart, it is in the wrong place.

*Basis for 12 days:* ETL (1) · promote the table with a real migration and drop the rebuild
overwrite (2) · admin API + authorisation + audit (4) · UI (2) · reconciliation report against
v3's employee registry (2) · the deactivation/cycle edge cases `EscalationService` already
enforces (1).

### Option (c) — approximate it with v3's jurisdiction model — **does not work. Do not fund it.**

The candidate is `access_jbac_rules` (jurisdiction-based access control), which is the one
thing in v3 with a hierarchy and an `parent_implies_children` flag. Its columns, read today
from the live schema:

```
tenant_id · name · path_pattern · methods[] · enforcement ·
parent_implies_children · extract_jurisdiction jsonb · description
```

**It authorises API paths by the jurisdiction extracted from a request.**
`parent_implies_children` refers to the **boundary** hierarchy — a ward is inside a city is
inside a state. It cannot express "PGGRO1 reports to PGLME2", and no amount of configuration
makes it. Both `access_jbac_rules` and `access_rbac_rules` hold **0 rows**; no endpoint on
`d3-accesscontrol` answered any of the five probed paths.

`employee_jurisdiction_v3` is the same story: boundary containment, **0 rows**, one jsonb blob.

**The substitution is not merely unavailable — it is wrong.** In the lab, **all four `pg`
employees share the jurisdiction `PG_STATE`/`SUN01_LOCALITY` under hierarchy `ADMIN`, and
three of them sit at different rungs of the same reporting chain.** A boundary-derived
"supervisor" would make PGGRO1, PGLME2 and PGLME3 peers — and `EscalationService` would then
throw `ESCALATION_HIERARCHY_CYCLE`, because the escalation target would be a current assignee.
Jurisdiction answers *where* someone works. Escalation asks *who they answer to*. They are
different graphs over the same people and one does not approximate the other.

### Recommendation

**Ask DIGIT for (a) and cost (b) as the fallback.** State the ask precisely — one nullable
`reporting_to` on `employee_v3`, exposed on the employee read — because it is small, it is
clearly in the right place, and it is the difference between 3 days and 12 plus a permanent
drift problem. **Until it is answered, treat escalation and TEAM-inbox visibility as
unmigrated, and disable them explicitly rather than letting them fall back:**
`PGR_ESCALATION_ENABLED=false` already does the first. **The second has no switch — it
degrades to the tenant-wide view — and that is a bug to fix before the cutover, not after.**

---

## 5. What the POC established, and what it did not

Established live (`poc-employee-report.md`):

- The employee map **works**: CCRS asked about PGGRO1, got a real user from `d3-employee`
  instead of `{"user":[]}`.
- **It did not fix the assignee.** `assigneeName` is `roles.get(0).getName()`, and v3's
  employee model has **no roles**. It stayed `"NA"`.
- Carrying the migrated **roles** in the same map flipped `ASSIGN`/`RESOLVE` to `"Employee"`,
  matching the 2.x control exactly. **The blocker is identity-plan C2, not H1/H2.**
- `APPLY` stayed `"NA"` in both shimmed runs, because there the actor is the **citizen** and
  the **individual** registry has no roles either. **Roles are the blocker on both sides of
  the registry split**; fixing employees alone leaves half the events wrong.
- **The lifecycle test cannot see any of this.** `compare.sh` reported an identical delta for
  the with-roles and without-roles runs, and `diff` of their comparison views was **0 lines**.
  The regression is visible only on `complaints.domain.events`.

Not established, and needing its own work:

- Whether `employee_jurisdiction_v3.boundary_relation` can carry `(hierarchy, boundaryType,
  boundary)` — **0 rows exist anywhere to look at.**
- Whether `d3-employee` validates department/designation against MDMS-v2 (the POC wrote rows
  by SQL and never exercised the validator).
- Anything about Bomet. **egov-bomet was not contacted.**
