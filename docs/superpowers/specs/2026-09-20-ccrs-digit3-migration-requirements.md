# What it will take to move CCRS from DIGIT 2.12 to DIGIT 3.0

**Date:** 2026-09-20 · **Audience:** the eGov/DIGIT platform team, and whoever plans Bomet and Nairobi
**Evidence box:** egov-subhashini, tenant `pg` (lab) · **Source/specs:** egov-server
(`/root/code/Citizen-Complaint-Resolution-System`, `/root/digitnxt-new/digitnxt/`)

This document synthesises four investigations: the hands-on hybrid run
(`docs/superpowers/specs/2026-09-20-ccrs-digit3-hybrid-findings.md`, in this repo), the dependency
servability study (localization, MDMS, HRMS/employee, url-shortener, notification), the identity
sizing study, and the data-volume study. Every claim below is traceable to one of those or to a
command re-run against the live box today. Nothing here is estimated where it was not measured, and
where it cannot be sized on this data the document says so instead of guessing.

---

## Page one — what a busy reader needs

**The headline.** Two of CCRS's HTTP dependencies (idgen, boundary) are ready for DIGIT 3.0 today.
The rest are not, and the reason is not translation quality. **DIGIT 3.0 has no complaint service in
its release, no organisational hierarchy, no self-loop workflow transitions, no terminal-state
concept and no message broker.** Those are not CCRS work items at any budget; they are requests to
the DIGIT team. Until they are answered, a CCRS cutover is blocked on an application that has not
been written, not on an ETL.

**The three categories.** Every gap found was sorted into exactly one of three buckets. Of the **95
distinct `(dependency, field)` findings** in `/opt/hybrid-shim/gaps.jsonl` (applying the last-entry
rule — §2 of the hybrid-findings doc):

| category | count | who owns it |
|---|---|---|
| **(a) Shim artefacts** — exist only because a throwaway translator was in the path | **38** | **nobody. These are not work items.** |
| **(b) Work items** — real things to build or change | **15** in the gap log, **28** once the data/identity/dependency studies are included | the CCRS team |
| **(c) Missing concepts and build defects in DIGIT 3.0** | **42** (28 missing concepts, 14 defects) | the DIGIT team |

Read that top row carefully. The duplicate `Boundary`/`boundary` key, the minted unsigned JWT, the
synthesised `ResponseInfo` envelopes, the faked `userName` and `createdDate`, the four router
negative-test paths — **40% of everything the gap log contains is scaffolding, and a real migration
would never produce any of it.** They were logged because the exercise logged everything. Anyone
costing this work from a raw read of `gaps.jsonl` will overstate it by roughly two-fifths.

**What is proven.** Three consecutive green complaint lifecycles on v3 idgen + boundary + workflow;
33 of 55 complaints in the live 2.x database carry v3-minted `9000xx` ids. **What is not proven:**
the lab is tenant `pg`, one complaint type, one locality, one citizen, one employee holding every
role, 55 complaints, **zero attachments on either stack — the filestore path is UNTESTED, not
proven** — no load testing, and the UI was never pointed at any of it.

**Ordering.** Seven irreversible steps were identified. Identity moves **first** (it is the only one
no shim can help with) and the point of no return is the day password resets go out. Workflow moves
**last among the flippable dependencies**, and only into a tenant drained to zero in-flight
complaints. **A staged cutover is impossible per dependency and possible per tenant — but as a big
bang within that tenant, with the 2.x stack retained read-only for history and audit.**

**The biggest risk is not failure — it is success that is silently partial.** v3's individual search
accepts `?userId=` and ignores it, returning the whole tenant with HTTP 200. v3 silently ignores a
caller-supplied `id` on create. This box already holds **7 individuals with `userid` set against
zero Keycloak users outside `master`**, with `KEYCLOAK_ENABLED=true` and no realm: nothing errored,
nothing was logged. **Reconciliation instrumentation must exist before the first migration step, not
after** (§4).

**Four earlier beliefs are wrong and are corrected in §5** — including "three v3 services are
missing" (they were only absent from the compose file) and "CCRS depends on MDMS v1" (it has been on
v2 behind an nginx rewrite for longer than this exercise has existed).

---

## 1. The three categories

### (a) Shim artefacts — not work items

These exist only because a stateless translator sat between CCRS and DIGIT 3.0, reconstructing the
2.x wire format request by request. In a real migration CCRS's own client code is changed once, or
the service is called natively, and **none of the following exists at all.** They are listed so that
nobody re-discovers them in the gap log and prices them.

| # | dependency | artefact | why it is not work |
|---|---|---|---|
| 1 | boundary | duplicate lowercase `boundary` key emitted alongside the real `Boundary` | the shim emits the list twice to satisfy a brief-specified assertion; safe only because pgr-services disables `FAIL_ON_UNKNOWN_PROPERTIES` |
| 2 | boundary | `boundary` → `Boundary`, `tenantBoundary` → `TenantBoundary` key renames | one-line field mappings in a DTO, once, not a runtime translation |
| 3 | boundary | leaf `children: []` reinstated where v3 omits the key | a null-check in the consumer |
| 4 | boundary | relationship-node `auditDetails` dropped | 2.x emits none on that endpoint; v3 emits more than 2.x did |
| 5 | boundary | the duplicated root tree inside `TenantBoundary[0]` not reproduced | a 2.x quirk, not a v3 gap |
| 6 | boundary | no translator for `/boundary-service/boundary/_create` | CCRS never calls it |
| 7 | idgen, boundary, user, workflow | **`ResponseInfo` synthesised (4 entries)** | DIGIT 3.0 has no `ResponseInfo` envelope anywhere. A migrated caller stops expecting one |
| 8 | idgen | `format` dropped | 2.x also discarded the per-request pattern in favour of MDMS |
| 9 | idgen | `config.sequence.start` raised 6000 → 900000 by hand | explicitly cosmetic; the real item is the ID authority (W3) |
| 10 | workflow | **unsigned `{"alg":"none"}` Bearer JWT minted per request** | forged because v3 reads roles only from `realm_access.roles` and does not verify the signature on this build. A real caller presents a real Keycloak token |
| 11 | workflow | `BusinessServices[]`, `ProcessInstances[]` envelopes rebuilt from v3 rows | shape reconstruction |
| 12 | workflow | `processCode` substituted as `PGR`; `history=true` passed through | correct only because pgr-services is the sole caller |
| 13 | workflow | the nameless 2.x `START` state given a name | a 2.x data quirk (`state: null`) |
| 14 | workflow | the 55/193/11 `compare.sh` fact diff on `service.processInstance` | evidence of a shape difference, not a defect |
| 15 | user | `userName` faked from the mobile number; `tenantId` taken from the request | the shim had no session and no user store |
| 16 | user | `createdDate` synthesised (2 entries) | done because `UserUtils.parseResponse:79` NPEs on null — **the NPE itself is a work item (W20)**; the fake is not |
| 17 | user | `name` split on the first space into `givenName`/`familyName` | a guess the shim had to make; **the name model is a work item (W19)** |
| 18 | user | `active` and `sort` filters dropped | v3 has no equivalents and CCRS does not depend on them |
| 19 | user | no translator for `/user/_details` | the shim covered three paths; the *finding* that authentication does not move is a work item (W25), the missing translator is not |
| 20 | user, workflow | the lab's citizen auth accepts any password; OTP never validated | a property of this 2.x demo box (OTP mode, enforcement off), not of either platform. **It also means the lifecycle's first step proves less than it appears to** |
| 21 | workflow | `SLA_ESCALATE` and a `PENDINGATSUPERVISOR` state | named in the original task brief as expected gaps; a grep of the whole CCRS repo shows **neither exists in this product**. Phantom findings |
| 22 | shim | `X-Tenant-ID` / `X-User-ID` derived from the request body | a stateless proxy had no context; a real caller sets them |
| 23 | shim | four router negative-test paths (`//user/_search`, `/egov-nosuch/v1/_search`, `/some/unknown/path`, `/egov-mdms-service/v1/_search`) | deliberate negative tests of the shim's own router |

**38 of the 95 gap-log findings are in this class.** Two further entries — the shim's inability to
page a >100-row result on either the individual or the workflow search — are half artefact (a
one-request-in/one-request-out translator cannot page) and half real (§1c, D11); they are counted
under (c).

### (b) Work items — what someone must actually build

Sized by what was measured. Where a volume could not be measured on real data it says so.

#### v3 configuration

| # | item | size / basis |
|---|---|---|
| **W1** | **Register CCRS's PGR process definition against a role vocabulary v3 can satisfy.** The definition already registered in v3 names `GRO`, `PGR_LME`, `PGR_VIEWER`, `SYSTEM`; **none of the four exists as a realm role.** Either mint the 12 missing codes into every tenant realm, or rewrite the definition to v3's `COMPLAINTS_ADMIN`/`RESOLVER`/`ASSIGNER`. **There is no third option** | 15 CCRS role codes vs ~75 realm roles per realm; **overlap is 3** (`CITIZEN`, `EMPLOYEE`, `SUPERUSER`). **DECISION GATE** |
| **W2** | **Per-tenant v3 individual config** — `nameRegex` (the default `^[A-Za-z ]+$` rejects initials, dots, hyphens and digits, all of which 2.x accepts), `uniquenessCriteria` on mobile (unset today, so v3 will store duplicate mobiles while CCRS treats mobile as identity in `upsertUser`), `gender` (mandatory in the build, optional in the spec) | one `POST /individual/v3/configs` per tenant; 6 tenants on this box |
| **W3** | **ID authority cutover** — freeze the 2.x allocator (including `digit-gatus`, which POSTs it every 60s for an id nobody consumes), take the high-water mark, seed the v3 template above it, move the format definition out of MDMS `common-masters.IdFormat` into the template store and **delete the MDMS entry so nobody edits a dead one**, re-point or retire the monitoring check | `seq_eg_pgr_id` was 5,348 when the hybrid doc was written, **5,507** at the data study, **5,518** today. Two live allocators over one ID space separated by a hand-picked offset nothing enforces |
| **W4** | **url-shortener bootstrap** — a per-tenant config row must exist before any shorten call (`404 NOT_FOUND` otherwise), and the service builds `shortUrl` from **its own env**, ignoring a supplied `baseUrl`; today it emits `http://localhost:8080/...`, which is useless in an SMS | 1 config row per tenant + 1 env var |
| **W5** | **Mount Vault's transit engine before the first individual write.** Every create failed `500 ENCRYPTION_ERROR` until it was mounted — and `d3-individual` passed every health and readiness check throughout | runbook line, hours. It is mounted on this box now (`transit_eae1d11a`) |
| **W6** | **Deploy and Kong-route the services that were only missing from the compose file** — localization, employee, url-shortener — plus **otp, which is not in the compose file and has no image deployed** although `d3-account` points at `localhost:8107/otp` and citizen login depends on it | 3 done here (§6), otp unbuilt. **No Kong routes were added for any of them** |
| **W7** | **Definition hygiene** — the live 2.x PGR `BusinessService` carries a `CANCELLED` state (`isTerminateState=true`) that **no action in any state reaches**. Do not carry dead config into v3, where a definition cannot be deleted once referenced | trivial, but one-way |

#### Data migration jobs

| # | item | lab volume | real-tenant volume |
|---|---|---|---|
| **W8** | **Boundary, per tenant and per hierarchy.** v3's store was empty; the 5 boundaries + 5 relationships + 1 hierarchy in it today were **hand-transcribed** (`createdBy = hybrid-shim-task4`). Includes code reconciliation: `PG_CITYA` exists under `pg` as a City in a 5-level hierarchy **and** `PG_CITYA_ADMIN_CITY` under `pg.citya` as the root of a different 3-level one | 13 boundaries / 13 relationships / 2 hierarchies. **`JLC477` is referenced by 2 complaints and exists in no boundary table on either stack** — an already-dangling FK inside 2.x that the migration must invent or fail | **not measured** |
| **W9** | **Localization.** A direct `INSERT…SELECT`: the v3 `localisation` table is a column-for-column match of the 2.x `message` table plus a generated `uuid` | **7,739 rows**, of which **4,772** are in the only two modules CCRS reads (`rainmaker-pgr` 2,572, `rainmaker-common` 2,200) | Bomet carries substantial `hi_IN`/`en_IN`/`fr_FR`/`pt_PT` sets against this box's one populated locale per tenant. **Not measured** |
| **W10** | **MDMS.** **Blocked** — see M10. The 2.x store holds 4,856 rows across 41 schemaCodes and 85 schema definitions; the release notes call v3's model a move from file-backed config to an API-driven one, i.e. a redesign, not a copy | 4,856 rows; `RAINMAKER-PGR.ComplaintHierarchy` = **8** | **`ke` carries 249 ComplaintHierarchy rows** (76 categories + 173 leaves) and `ke.ige` 138, proven on the live stack 2026-06-25. **An order of magnitude past the lab** |
| **W11** | **Identity: users and roles.** `eg_user` → `individual_v3` **plus a Keycloak user per tenant realm** (a person in three tenants fans out to three realm users with nothing linking them); `eg_userrole_v1` → realm-role mappings | 20 users / 6 tenants / **121 grants** / 15 role codes | **not measured** |
| **W12** | **PII re-encryption.** Decrypt with the tenant's `egov-enc` symmetric key, re-encrypt through Vault transit, **and recompute `hashedmobilenumber` yourself** — 2.x mobile ciphertext is deterministic (that is how 2.x searches by mobile without decrypting) and Vault transit is not | **6 symmetric + 6 asymmetric keys, one pair per tenant** = six independent one-way steps; 3 PII columns × 20 rows | **not measured** |
| **W13** | **Rewrite `accountid` on every complaint.** `EnrichmentService.java:64` stamps the 2.x user uuid onto every complaint; it is read back in five places. Destructive in place **unless the old value is kept in a shadow column** | **55 complaints**, 1 distinct accountid per tenant | **not measured** |
| **W14** | **A durable `2.x uuid → (realm, individual id, keycloak sub)` map, kept forever.** Nothing in either stack holds this. Without it, a re-run mints duplicate people with new ids and no way to tell which is canonical | new component | — |
| **W15** | **Workflow: drain, do not migrate.** Nothing moves process instances in either direction and **v3 instances cannot be deleted at all** | `pg` holds 96 rows in 2.x and 48 in v3. **21 of 55 complaints have zero 2.x workflow rows; 10 of those are still in flight; 900018–900021 have exactly one 2.x row each and no v3 instance at all** | — |
| **W16** | **Audit: freeze and retain, do not migrate.** Every row carries an `integrityhash` computed over 2.x's `keyvaluemap` for a 2.x `entityname`, chained across multi-row objects. Rewriting under new table names invalidates every hash and **the chain cannot be re-derived without the original writer's secret** | **2,435 rows, 2,435 hashed, 105 chained objects**; v3 `audit_logs` = 0 and no audit service deployed | — |
| **W17** | **Attachments — build it and prove it.** `eg_filestoremap` = 0 rows, `eg_pgr_document_v2` = 0 rows, and **both MinIO buckets are empty** (`egov-rainmaker` 4.0K/0 objects, `digit` 4.0K/0 objects), verified again today. **This path is UNTESTED, not proven easy.** Real tenants have attachments: `deploy/expectations/bomet.json` lists `ui.citizen.attachmentDetailRender` as **required** | **0 objects on either stack** | **unknown and unmeasured** |
| **W18** | `eg_url_shortener` — migrate or retire | 76 rows | — |

#### CCRS code changes, by file

All paths relative to the repo root.

| # | file(s) | change |
|---|---|---|
| **W19** | `backend/pgr-services/.../service/EnrichmentService.java:64`; `validator/ServiceRequestValidator.java:129`; `policy/AccessPolicyRegistry.java:281`; `policy/PolicyInputBuilder.java:55`; `repository/rowmapper/PGRQueryBuilder.java:157,275` | `accountId` stops being a 2.x `eg_user` uuid and becomes the individual id. Five read sites, one write site |
| **W20** | `backend/pgr-services/.../service/UserService.java` (`enrichUser:110`, `getFirstRoleNameByUuid:241`, `upsertUser`); `util/UserUtils.java:79`; `service/ComplaintDomainEventService.java:226` | the individual registry has **no roles and no user type**; `parseResponse` parses `createdDate` with no null check (NPE); **`getAssigneeName` will emit the literal string `"NA"` on every domain event once user moves.** Nothing errors, nothing is logged, nothing fails a test — this is the kind of thing that surfaces six months later as *"why do all our notifications say NA"* |
| **W21** | `backend/pgr-services/.../util/NotificationUtil.java:166-178` | `getShortnerURL` binds the response to `String.class`, expecting plain text; v3 returns JSON. **Make CCRS parse JSON.** Do not let a shim hand a Java `String` binding something it must never get wrong — if it ever returned the object, a citizen receives `{"id":…,"shortUrl":…}` as their complaint link, with no type error, no exception and no 4xx |
| **W22** | `backend/pgr-services/.../util/NotificationUtil.java:73-79` + `application.properties:47-51` | localization: path `/localization/messages/v1/_search` → `/localization/v3/messages`, and `tenantId` moves from a **query param to the `X-Tenant-ID` header** (v3 ignores the query param and fails `400`). `locale` and `module` pass through and genuinely filter. **This is the smallest change of any dependency examined — smaller than idgen** |
| **W23** | `backend/pgr-services/.../util/HRMSUtil.java` + `PGRConstants.java:207-222` + `eg_pgr_hrms_projection` | `getDepartment()` reads a dated `assignments[]` array; v3 employee has a **scalar `department` column and no assignment history**. `getSupervisorUuid()` **cannot be fixed on the CCRS side against v3 as it stands** — see M3. Either CCRS owns the supervisor edge itself (extend `eg_pgr_hrms_projection`, feed it from a CCRS-owned source, stop asking HRMS) or escalation is disabled via `pgr.escalation.enabled` as an **explicit, accepted capability loss** |
| **W24** | `backend/pgr-services/.../service/WorkflowService.java:95,131,244,264`; `MigrationUtils:147`; `NotificationService.java:663`; `EscalationService.java:278` | one env var serves **three paths in five call shapes**. `enrichWorkflow` joins every complaint in a result set to its workflow in one `?businessIds=<csv>` call and throws `WORKFLOW_NOT_FOUND` unless the counts match — **blocked on M6**; also v3 pages every list and caps `size` at 100 |
| **W25** | `backend/identity-bff/src/modules/managed-accounts/*` (`managed-account-service.ts`, `digit-user-client.ts`, `digit-admin-session.ts`), `reconciliation/subject-sync.ts`, `reconciliation-service.ts`, `authentication/token-verifier.ts` | **identity-bff is the right half of the bridge pointed the wrong way.** It authenticates against Keycloak and then mints an **`egov-user` 2.x opaque token** via `grant_type=password` — it terminates at precisely the component v3 deletes, and its design principle is that the Keycloak JWT **never** reaches the API path. v3 reads action roles **only** from a Bearer JWT. To bridge to v3 you invert that principle. `managed-accounts/` is discarded; `authentication/`, `sessions/`, `organizations/`, `reconciliation/` are reusable and are **the hardest part to rewrite**. Also `MANAGED_USER_TYPE = "EMPLOYEE"` (line 35): it is employee-only, and **CCRS's citizens are exactly the population that fails with `INVALID_ACCOUNTID`.** It is **not deployed on subhashini** — not a bridge you switch on, one you finish, redirect and extend |
| **W26** | `digit-ui-esbuild/packages/libraries/src/services/auth/KeycloakAuthAdapter.js` (331 lines); `digit-ui-v2/src/api/keycloak.ts` (345 lines); `local-setup/kong/kong.yml`; `local-setup/docker-compose.egov-digit.yaml:2911-3003`; `local-setup/tests/e2e/kc-parity/*` | both UI adapters target the retired `/kc` exchange. Kong's global DIGIT auth plugin **rejects a Keycloak JWT before anything can use it**. The kc-parity harness compares credential *kind* (`uuid` vs `jwt`); under v3 every call is `jwt`, so its baseline must be re-cut |

#### New components

| # | component | note |
|---|---|---|
| **W27** | **Reconciliation instrumentation** — counts `eg_user` vs `individual_v3` vs `user_entity` per tenant and asserts the W14 map is total and injective, run after **every** migration step | **must exist before the first migration step.** See §4. It does not exist today |
| **W28** | **A decision on the notification path** — CCRS makes **no HTTP notification call**: it emits to Kafka (`complaints.domain.events`, `egov.core.notification.sms`, `persist-user-events-async`) and `backend/novu-bridge` consumes it, with retry and DLQ topics, resolving recipients through `egov-user`. `d3-notification` is a different architecture (synchronous `POST /sms/send` with a server-side template store). Adopting it means rewriting the path, moving templates out of MDMS masters, and deciding what happens to novu-bridge. **That is a product decision, not a migration task** |

### (c) Missing concepts in DIGIT 3.0 — requests to the DIGIT team

**These are not tasks for the CCRS team.** No amount of good engineering on the CCRS side resolves
them, because there is nothing in v3 to map onto. They are stated here so they can be raised as
platform requirements.

| # | missing concept | evidence |
|---|---|---|
| **M1** | **No PGR / complaint service in the DIGIT 3.0 release.** There is no `pgr` directory in `digit3/src/services/` (17 services: account, apportion, billing, boundary, employee, filestore, idgen, individual, kong, localization, notification, notify, otp, pg-service, registry, url-shortener, workflow) and **no `pgr.yaml` among the 16 specs in `digit-specs/v3.0.0/`.** The only candidate is a compose entry (`deploy/local/docker-compose.yml:709`, `container_name: d3-pgr`) pointing at **`sruj307/pgrown30:pgr-stable-latest`** — a personal Docker Hub namespace, outside the `master-ce7a0b0` generation every other v3 service carries, storing complaints in the registry under `PGR_REGISTRY_PGR_STORAGE_SCHEMA_CODE: pgr2`. **It has never been deployed or evaluated here, and CCRS's grievance model was never compared against it.** Until DIGIT ships a PGR service in the release, the destination for CCRS's primary entity has to be built |
| **M2** | **Self-loop actions are stored and then refused.** CCRS models both COMMENT and ESCALATE as `nextState == currentState`. v3 **accepts all six at registration** and refuses every one at runtime with `409 invalid state transition: no transition`. The rows are in `d3-postgres`: COMMENT at PENDINGATLME / PENDINGFORASSIGNMENT / PENDINGFORREASSIGNMENT / REJECTED / RESOLVED, ESCALATE at PENDINGATLME. **Standalone citizen commenting has nowhere to live** — a comment attached to a real transition survives as its `comment` string; one that does not move state does not. **Request:** a self-loop transition that records a row without moving state, or a first-class annotate-without-transitioning call. Neither exists in `workflow.yaml` |
| **M3** | **No `reportingTo`, and no organisational hierarchy of any kind.** `employee_v3` is one row, one current state: `id, code, user_id, individual_id, status, employee_type, date_of_appointment, department, designation, is_active, tenant_id, …`. No column, no field in `EmployeeCreateRequest`/`UpdateRequest`/`Response`, and **`grep -rin 'reportingTo|reporting_to|supervisor' digit-specs/v3.0.0/` returns zero hits across all 16 specs.** CCRS escalation re-targets a complaint to the next supervisor up that chain. A shim could only invent one — and an invented supervisor routes a real citizen's complaint to the wrong person, silently. **That is the one category of fake this exercise exists to refuse.** **Request:** an org-hierarchy edge (employee → supervisor, jurisdiction- and time-scoped) on the v3 employee model |
| **M4** | **No state-type column, so nothing in v3 can say a complaint is closed.** `TERMINAL_SUCCESS` / `TERMINAL_FAILURE` are accepted and silently rewritten to `INTERMEDIATE`; the `states` table carries `is_initial, is_parallel, is_join, branch_states` and nothing else. CCRS is unaffected because it reads `applicationStatus`, but **a v3-native consumer of this process definition cannot compute completion** |
| **M5** | **`additionalDetails` is accepted and discarded.** Declared on both `ProcessDefinitionInput` and `StateInput`; no column exists; a registered definition reads back without it at both levels. **This matters more than the fields it was carrying: it is the escape hatch the spec advertises for every 2.x concept v3 lacks, and it does not exist. Nothing can be round-tripped through it** |
| **M6** | **No multi-entity workflow search, therefore no employee inbox.** `GET /workflow/v3/transition` serves exactly one `entityId`: the comma form returns `totalCount 0`, the repeated form honours only the first value. Measured A/B on the same box in the same minute with only `EGOV_WORKFLOW_HOST` changed — 2.x: **HTTP 200, 2 wrappers**; v3: **HTTP 400 `WORKFLOW_NOT_FOUND`, 0 wrappers**. **Every employee inbox holding ≥2 complaints is dead.** The happy-path lifecycle stayed green throughout, because both of its searches are scoped to one `serviceRequestId` — a purpose-built negative instrument had to be written to find this at all |
| **M7** | **No assignable individual id.** A caller-supplied `id` on create is **silently ignored** and v3 mints its own (sent `aaaaaaaa-…`, got `629b9122-…`, HTTP 201). Same for `individualId`. Storing the 2.x uuid in `individual.userId` does not help, because the `?userId=` filter is accepted and ignored (M8). Storing it in `additionalAttributes` is write-only. **All three mappings were tried against the live service.** **Request:** honour a caller-supplied id on create, or make `userId` a real filter |
| **M8** | **Query filters accepted and silently ignored, answering 200 with the whole tenant.** `?userId=`, `?givenName=` and even `?wibble=` all return every individual in the tenant, while `individual.yaml` documents those filters as real **and** promises 400 for unknown parameters. Neither statement is true of this build. **A shim that trusted the spec would ask for one citizen and hand CCRS a different one, silently.** This is the most dangerous single behaviour found in either v3 service |
| **M9** | **Process definitions cannot be deleted once referenced, and process instances cannot be deleted at all.** The workflow service exposes `POST`/`GET /workflow/v3/transition` and nothing else. Two inert `ZZPROBE` definitions are **permanent residents** of this store. A mistaken or superseded workflow can be orphaned but never retired; any instance written with a wrong assignee identity is permanent |
| **M10** | **The v3 MDMS serves no API and holds no data.** `d3-mdms-v2` answers TCP and returns JSON, and **every path probed returns `400 NoResourceFoundException: No static resource <path>`** — Spring falling through to the static-resource handler, i.e. nothing was mapped. The controllers are physically in the jar (`MDMSController`, `MDMSControllerV2`, `SchemaDefinitionController`); startup logs `Exposing 1 endpoint(s)` and no mapping lines. **The artefact name is the tell: `/opt/egov/egov-mdms-service-test-1.3.3-SNAPSHOT.jar`.** `eg_mdms_data` has 0 rows. There is **no MDMS source in `digit3/src/services/`**, the spec says `/mdms/v3`, the deployed client says `/mdms-v2/v2`, and the build serves neither — **three different answers, none live.** This is *unimplemented*, not *unservable*: the same codebase works on 2.x. **Until DIGIT publishes a non-`test` build, no shim can be written and no claim about MDMS translatability can be tested** |
| **M11** | **No broker.** The v3 stack defines no Kafka/Redpanda service. CCRS is not only an HTTP client: complaints reach Postgres through `egov-persister` consuming topics, and notifications through `complaints.domain.events` → novu-bridge. **None of that can be re-pointed by an environment variable, because none of it is HTTP.** The shim methodology has a hard boundary — it can only ever measure the HTTP half of CCRS. Persistence, indexing, notification dispatch and domain events were not tested and are not testable this way |
| **M12** | **No callback/URI model, no soft-deleted actions, no `nextActions`, no `moduleName`, no `rating`/`previousStatus`/`entity`, no `docUploadRequired`/`isStateUpdatable`, assignees as bare id strings, `assigner` accepted then discarded, batch transitions unsupported.** Each is a 2.x field or capability with no v3 counterpart. Individually small; collectively they are why a v3 transition reply cannot be reconstructed into a 2.x one without a second call per transition — which a translator cannot express |

#### Defects in the shipped v3 build — also requests to DIGIT, but cheaper

These are fixable in v3 without changing its model: multi-code boundary search returns `[]` though
`boundary.yaml` declares `codes` as an array; `Geometry.coordinates` spec/implementation mismatch;
`PUT /idgen/v3/template` refuses to change `start` on a GLOBAL template, so reseeding is a
destructive DELETE + POST that discards the high-water mark; `_updatenovalidate` has no v3
equivalent because `PUT` is optimistic-concurrency and learning the `version` needs a second call;
`gender` mandatory though the spec says optional; `nameRegex` too strict by default;
`uniquenessCriteria` unset; action `roles` **enforced but undocumented** in `ActionInput`, readable
only from a Bearer JWT whose signature is **not verified** (`X-Roles` → 403, unsigned Bearer → 200);
`nextActions` never returned; a search by `entityId` alone refused (`processCode` mandatory); a
refused first transition leaves a stranded `INITIAL` instance parked at `START`, a state no 2.x
`applicationStatus` maps to; page caps at 100 with no unbounded internal fetch.

**A note on the signature.** The shim works today because `{"alg":"none"}` is honoured. That is a
defect, not a licence: **a fixed build will verify, and anything relying on a forged token dies at
that point.**

---

## 2. What is ready today, and what is not

| dependency | verdict | evidence |
|---|---|---|
| **idgen** | **Ready today.** One endpoint, one caller, no second API behind the variable | three green lifecycles at switchover and every run since. The ids CCRS receives **cannot** have come from 2.x: the v3 template starts at 900000 and `seq_eg_pgr_id` is at 5,518. **33 of 55 complaints carry `9000xx` ids** |
| **boundary** | **Ready today** for the single-code lookups CCRS makes, once the geography is migrated | green after one instructive red run. Four key renames and a data migration is the whole list |
| **localization** | **Servable, and the smallest change of any dependency** — but **no flip was performed and no lifecycle was run against it.** "The API covers the call" is a contract claim, not a behavioural one | probed live: filters genuinely filter (3 seeded rows, filtered search returned exactly 1, unfiltered 2); unknown values correctly return `[]`; response shape is compatible (the extra `uuid` is inert) |
| **url-shortener** | **Servable only with a CCRS code change** (W21) plus config bootstrap (W4) | working end-to-end once bootstrapped |
| **workflow** | **Green on the happy path, and not ready.** Kills citizen COMMENT, kills ESCALATE, kills every inbox holding ≥2 complaints, and the flip is irreversible for in-flight complaints | M2, M6, W15 |
| **HRMS → employee** | **Not servable.** `department` could be shimmed by synthesising a one-element `assignments[]`; `reportingTo` cannot be, at any effort | M3. **Employee writes were never exercised** — they need a Keycloak bearer token — so the create/update contract, the jurisdiction model and whether `department`/`designation` accept CCRS's vocabulary are all unverified |
| **user / identity** | **Reverted; must stay reverted.** Not a shim problem | `FAIL create 400 INVALID_ACCOUNTID`. `EGOV_USER_HOST` moves three pgr-services paths and **not authentication**: `POST /user/_details?access_token=` (205 calls) and `POST /user/oauth/token` (120) come from **kong-gateway**, through its own upstream |
| **MDMS** | **Cannot be assessed.** Not because of a field mismatch — because the v3 service serves no endpoints and holds no data | M10 |
| **notification / all Kafka paths** | **Not a re-pointable dependency at all** | M11, W28 |
| **enc-service** | **Not a re-pointable dependency** — v3 encrypts inside the individual service via Vault transit. Different architecture, not a missing endpoint | W12 |

### Be honest about what the proof rests on

- **One tenant, `pg`, on a lab box.** Not `mz` — `mz` is half-onboarded (no `ComplaintHierarchy`, no
  boundaries, no citizen) and cannot complete a lifecycle even on unmodified CCRS. **Nothing
  multi-tenant was exercised**, and tenancy moves from a body field to a header in v3.
- **One complaint type** (`BurningOfGarbage`), **one locality** (`SUN01_LOCALITY`), **one citizen**,
  **one employee holding GRO + PGR_LME + CSR simultaneously.** Role separation between assigner and
  resolver was never tested.
- **55 complaints; a 23 MB database.** A toy dataset.
- **Zero attachments.** Both MinIO buckets empty, both filestore tables empty, on both stacks,
  re-verified today. **The filestore path is untested.**
- **No load or concurrency testing.** Every measurement is one request at a time. Nothing is known
  about v3 under contention or about the 100-row page cap at real inbox sizes.
- **The UI was never exercised.** `digit-ui` is running and was never pointed at any of this.
- **Citizen authentication is not actually authenticating** — the lab returns 200 for any password.
- **Only the happy path, plus one purpose-built negative instrument.** That instrument found a fatal
  defect the lifecycle could not see. **There is no reason to believe it is the only one.** REOPEN,
  RATE, REJECT, REASSIGN and the escalation scheduler were never driven end to end.
- **The shim is not a migration path.** Stateless, unauthenticated, uncached, no retries, one
  request in and one out, and its 2.x envelopes are reconstructions matched against live 2.x
  replies — not contract guarantees.

---

## 3. Order and irreversibility

### The seven one-way steps

| # | step | where the point of no return is | measured today |
|---|---|---|---|
| **I1** | **Flip idgen to v3** | The moment the first `9000xx` id is written to a complaint. Re-pointing back re-issues ids v3 already minted; reseeding v3 is DELETE + POST, which discards the high-water mark | `seq_eg_pgr_id` = **5,518** and still climbing (gatus alone moves it); 33 of 55 complaints already carry v3 ids |
| **I2** | **Flip workflow to v3** | The first transition after the flip. Nothing migrates instances in either direction and **v3 instances cannot be deleted at all** | `pg`: 96 rows in 2.x, 48 in v3; 4 in-flight complaints (900018–900021) already have their APPLY in 2.x and **no v3 instance at all** |
| **I3** | **Re-encrypt PII into Vault** | Per tenant, at the moment its `eg_enc_symmetric_keys` row is retired. **Keeping the 2.x key is what makes rollback possible at all; destroying it destroys the 2.x data permanently** — and the release notes say to rotate keys during migration, so doing it properly *is* the irreversibility | 6 key pairs = **six independent points of no return** |
| **I4** | **Flatten the tenant** | The first merged write. Once `pg.citya`'s complaints, boundaries, users, HRMS records and MDMS overrides sit in one namespace with the parent's, "which of these came from the city?" is unanswerable **unless a provenance column is added first** | sub-tenants hold **no** workflow business service and 6–7 MDMS rows each — they *inherit*. Flattening either duplicates the parent's ~1,467-row master set into every child or discards the child's overrides |
| **I5** | **Copy audit rows** | The first rewritten row. **Do not take this step** — freeze and retain instead | 2,435 rows, 2,435 hashed, 105 chained objects |
| **I6** | **Register a v3 process definition** | Registration itself, once any instance references it (`409 … referenced by other resources`) | v3 `processes` = 3: `ZZPROBE1`, `ZZPROBE2`, `PGR`. The two probes are permanent |
| **I7** | **Decommission egov-user for Keycloak** | **The day password resets go out.** Every live opaque token dies; there is no dual-stack period, because 2.x validates live and v3 validates a signature | Keycloak `user_entity` = **1** (`admin`, realm `master`) against 20 users in 2.x. This is the dependency the hybrid run reverted |

Two further one-way facts that are not steps: **20 bcrypt password hashes cannot be carried over**
(Keycloak can import bcrypt via a custom hash provider, but v3's realm template does not ship one and
`d3-keycloak`'s provider directory contains no such jar — **assume every user resets**; for citizens
this is moot under OTP, for the 17 employees it is a coordinated reset), and **a v3 realm is
destroyed with the tenant** — `isActive:false` disables it, and `name` is immutable because the
tenant `code` **is** the realm name, so a mistyped tenant code is not renameable.

### A safe ordering

```
0.  Backup. pg_dump -Fc, with pg_restore --clean rehearsed.        <- the ONLY rollback
    This is the repo runbook's own opening line and it is the only
    undo that exists for anything below.

1.  Characterise the real tenant.  A read-only pass on Bomet's and
    Nairobi's own databases.  A PREREQUISITE, not an optimisation.

2.  Build the reconciliation instrument (W27).  Before any data moves.

3.  Freeze: no new users, no role edits.                           <- reversible to here

4.  Deploy the missing v3 services: localization, employee,
    url-shortener, otp.  Add Kong routes.  Mount Vault transit.

5.  Reference data, in this order: boundary -> MDMS masters ->
    localization.  Additive, idempotent, reversible by truncating
    the v3 side.  Boundary FIRST, because validateBoundary rejects a
    complaint whose locality is unknown -- and JLC477 must be
    invented here or two complaints fail validation.
    (MDMS is BLOCKED on M10.)

6.  DECISION GATE: 12 new realm roles per realm, or rewrite CCRS's
    process definition to v3's vocabulary (W1).

7.  Identity, as ONE unit -- CITIZEN realm, tenant realms, individuals,
    roles, key rotation per tenant with the 2.x key escrowed until the
    last verified read.  Build the W14 map.  (I3.)
    Dry-run into a scratch tenant first.  Run W27 after every step.

8.  Build identity-bff-v3 (W25); cut the UI adapters over.
    ======== POINT OF NO RETURN: password resets go out ========  (I7)

9.  Rewrite accountid on eg_pgr_service_v2, retaining the old value in
    a shadow column (W13).

10. Freeze the ID authority; seed the v3 template above the high-water
    mark; retire the gatus check.  (I1.)

11. Drain workflow to zero in-flight complaints, then flip.  (I2, I6.)
    This step dictates the freeze window for everything else.

12. Complaints last -- they reference all of the above.  BLOCKED on M1.

13. Audit: freeze and retain.  Start a fresh v3 chain at the cutover
    timestamp and document the seam.  Any other choice forges hashes.
```

Steps 0–7 are reversible. **Step 8 is the point of no return for the migration as a whole.**

Note the ordering disagreement this resolves: identity is the **first** thing to move, not the
fourth. The data study reached the same conclusion from a different direction (reference data is
additive and can precede it; everything transactional cannot).

### Is a staged cutover possible?

**Per dependency: no. Per tenant: yes — but only as a big bang within the tenant.**

- **A dependency cannot be re-pointed one API at a time.** `EGOV_BOUNDARY_HOST` serves two
  endpoints; `EGOV_WORKFLOW_HOST` serves three paths in five call shapes; `EGOV_USER_HOST` serves
  three paths and not authentication. Translating one and not the others produced **a plausible
  envelope for an untranslated call**: `BoundaryHierarchyExpander` expanded the employee's
  jurisdiction to the empty set and the inbox returned **zero complaints with HTTP 200 and no error
  anywhere** — and stayed wrong for 30 minutes, because that expansion is cached and falls back
  silently. **Count the endpoints behind every host variable before flipping it, and write a
  negative test for the call shape the happy path does not exercise.**
- **Workflow cannot be staged even within one tenant.** Nothing migrates instances; v3 instances
  cannot be deleted. Any complaint in flight at the flip has its history permanently split. **The
  only staging that works is to drain to zero in-flight complaints first** — which for a live
  grievance system means a freeze window sized by the slowest SLA, not by the ETL.
- **Identity cannot be staged at all.** Reverted, empirically.

So the unit of cutover is **one tenant, drained to zero in-flight complaints, moved whole, with the
2.x stack retained read-only for history and audit.** A rehearsal on a restored `pg_dump` is
mandatory, because six of the seven irreversible steps have no undo.

**And note what a staged plan cannot buy you.** There is no PGR service in the DIGIT 3.0 release
(M1). Until one exists, every tenant-level cutover is blocked on an application that has not been
written.

---

## 4. The biggest risk: a silent, partial migration

Three measured behaviours compound into one failure mode that nothing in either stack detects.

1. **v3's individual search accepts and ignores `?userId=`** — and `?givenName=`, and `?wibble=` —
   returning **the whole tenant with HTTP 200**, while `individual.yaml` documents those filters as
   real and promises 400 for unknown ones.
2. **v3 silently ignores a caller-supplied `id` on create** and mints its own, HTTP 201.
3. **This box already proves the consequence.** `d3-individual` runs with `KEYCLOAK_ENABLED=true`
   against a Keycloak whose only realm is `master`. `individual_v3` holds **7 rows for tenant `pg`,
   all 7 with `userid` populated**; `user_entity` holds **1 user, `admin`, in `master`**. **Seven
   people exist in the registry who can never log in. Nothing errored. Nothing was logged.**

A migration re-run, a partial failure, or an off-by-one in the W14 map therefore produces **duplicate
people, complaints reattached to the wrong citizen, and individuals who can never authenticate — with
HTTP 200 on every call and no error anywhere.**

This is not hypothetical pattern-matching. The same failure *shape* was recorded three separate times
in this exercise: the boundary-relationship envelope that expanded a jurisdiction to the empty set
and returned 0 complaints with HTTP 200; the employee inbox that is dead for ≥2 complaints and that
the happy-path lifecycle cannot see; and the individual registry that passed every health and
readiness check while being incapable of storing a single person (unmounted Vault transit).
**"Accepted and ignored" is the dominant failure mode of this v3 build** — a caller-supplied `id`, a
`userId` filter, `additionalDetails`, a state `type`, an `assigner`, a `count` on the non-bulk idgen
path: all accepted, all discarded, all with a success status.

**The mitigation is not care. It is instrumentation.** A reconciliation check that, per tenant,
counts `eg_user` vs `individual_v3` vs `user_entity`, and asserts the `2.x uuid → individual id` map
is **total and injective**, run after every migration step. **That instrument does not exist. It must
be built before step 7, not after** — after the fact, the evidence needed to rebuild the mapping is
exactly the evidence that was lost.

A close second: **rewriting `accountid` on the complaint table** (W13), because it is a destructive
update to production complaint data keyed on a map that the first risk can silently corrupt. Retain
the old value in a shadow column.

---

## 5. Corrections — earlier beliefs that turned out wrong

People will have heard the earlier version of several of these. State them as corrections.

**5.1 "Localization, employee and url-shortener don't exist in DIGIT 3.0." Wrong.**
Section 7 of the hybrid-findings doc records them as *"none — no service in the v3 stack."* That was
true of the **deployment** and false of the **platform**. All three had images published and pullable
the whole time, carrying **`master-ce7a0b0` — the exact generation tag of every running v3 service**.
They were simply **omitted from `digit3/deploy/local/docker-compose.yml`**. Three `docker compose up`
commands changed the answer: all three now run, healthy, schema-migrated (§6). The correct statement
is: *the compose file, not the build pipeline, is why they were missing.* Note this does **not**
extend to `otp`, which is genuinely undeployed and un-imaged while `d3-account` depends on it.

**5.2 "CCRS depends on MDMS v1, and v3 ships v2, so the envelope must be translated." Wrong, and this
is good news nobody had written down.**
`EGOV_MDMS_HOST=http://egov-mdms-service:8094/` points at a container running **`egovio/nginx:alpine`**,
whose config is 22 lines of rewrite:

```nginx
location /egov-mdms-service/ {
    set $mdms_upstream mdms-backend;
    rewrite ^/egov-mdms-service/(.*)$ /mdms-v2/$1 break;
    proxy_pass http://$mdms_upstream:8094;
}
```

CCRS POSTs `MdmsCriteriaReq` to `/egov-mdms-service/v1/_search`; nginx rewrites the prefix; an
**mdms-v2 backend answers**, and the same jar family correctly serves both the v1 envelope and the v2
flat `mdms[]` shape. Verified live on port 18094: v1 → 200, v2 → 200 with real rows. **The v1→v2
translation problem is already solved and already in production.** What blocks the move to v3 is not
the API shape — it is that the v3 copy of that same service is broken and empty (M10). Two
consequences: MDMS is less scary than it looked, and **the 2.x stack contains at least one
translation shim that predates `/opt/hybrid-shim` — audit for others before assuming any `EGOV_*_HOST`
points at what its name says.**

**5.3 "Plan from `digit-specs/v3.0.0/`." Wrong — spec, source and shipped image disagree on every
single service examined, in a different way each time.**

| service | spec says | source is | the build does |
|---|---|---|---|
| localization | path `/messages`, **no query parameters at all** | a **Java 25 / Spring Boot 4 Maven project** with an `application.properties` README | a **Go/Gin binary** serving `/localization/v3/messages` **and** honouring `locale` + `module` query params (confirmed today in `d3-localization`'s `[GIN-debug]` route table) |
| employee | jurisdictions nested under `/employees/{id}/jurisdictions` | Go | a **flat `/employee/v3/jurisdictions`** collection |
| url-shortener | config field `keyLength` | Go | demands **`shortKeyLength`** (`VALIDATION_ERROR … failed 'required' validation`) |
| MDMS | `/mdms/v3` | **no source in `digit3/src/services/` at all** | neither — no endpoints (M10) |
| boundary | `codes` is an array, min 1 | — | matches the raw string as one code, returns `[]` |
| individual | `gender` optional; unknown params → 400 | — | `gender` **mandatory**; unknown params → **200 and the whole tenant** |
| workflow | action `roles` undocumented in `ActionInput` | — | **enforces them**, readable only from an unverified Bearer JWT |

**Any migration plan written from `digit-specs/v3.0.0/` alone will be wrong. Every contract must be
verified against a running container.**

**5.4 "There is no PGR service anywhere in DIGIT 3.0, deployed or in source." Overstated — and the
two source reports disagreed on this.**
There is no `pgr` service directory in `digit3/src/services/` and no `pgr.yaml` among the 16 v3
specs, both confirmed today. But the v3 compose file **does** define one at line 709 —
`container_name: d3-pgr`, image **`sruj307/pgrown30:pgr-stable-latest`**, a Spring Boot service that
stores complaints in the v3 **registry** (`PGR_REGISTRY_PGR_STORAGE_SCHEMA_CODE: pgr2`), uses
workflow process code `PGR100` and idgen template `pgr`. It is **not running and has never been
evaluated.** The accurate statement is not "none exists" but: **the only PGR candidate in DIGIT 3.0
is an out-of-generation image from a personal Docker Hub namespace, with no published spec, never
deployed here.** That is still a blocker for M1 — but it is also the single most important thing for
the DIGIT team to clarify, because if `d3-pgr` is intended to be the destination, most of §1(b)
changes shape.

**5.5 "One of the five workflow call shapes appears in no source file." Wrong, and already corrected
in the hybrid-findings doc — repeated here because the wrong version circulated.**
The `history=true` shape was **missing from the task brief's inventory**, and was found by reading
the `egov-workflow-proxy` access log during a green 2.x run. But it is plainly in the source, at
`NotificationService.java:663` and `EscalationService.java:278`. The lesson is *watch the wire, then
confirm in the code* — not that the code was silent.

**5.6 "The shim's invented `roles[]` feed privilege decisions." Withdrawn.**
`gaps.jsonl` is append-only, so the retracted claim (line 526, `04:50:08Z`) is still sitting above
its own correction (lines 729 and 805, both opening `SUPERSEDES an earlier entry`). Exactly **four**
fields in the whole log carry two different actions and **all four are this same correction**
(`userName`, `user.type`, `user.roles[]`, `User.roles[] / User.type`). The corrected claim:
shim-supplied roles are **structurally unable** to reach an authorization decision —
`UserDetailResponse.user` is typed `List<org.egov.pgr.web.models.User>` and every authorization site
imports a different class, `org.egov.common.contract.request.User`, read from `requestInfo.getUserInfo()`
which the gateway fills from the 2.x token. Their only real consumer is a display string (W20).

> **Rule for anyone mining `gaps.jsonl`: grep the field name, sort by `ts`, take the LAST entry.**
> Stopping at the first match gives you a retracted claim. Applying that rule to the 1,042 lines
> present today yields **95 distinct `(dependency, field)` findings**, 99 `(dep, field, action)`
> triples, distributed `unmapped` 33 / `dropped` 30 / `faked` 18 / `defaulted` 14. **This is a real
> usability defect in the evidence being handed over**; a future run should write to a keyed store or
> mark superseded lines in place.

**5.7 Two gaps named in the original task brief do not exist in this product.** There is no
`SLA_ESCALATE` action and no `PENDINGATSUPERVISOR` state anywhere in CCRS — greps of the whole repo
and of the live 2.x `BusinessService` both come back empty. Do not carry them into a plan.

---

## 6. What was left running on egov-subhashini

Verified live immediately before writing this document.

| container | image | host port | uptime at verification |
|---|---|---|---|
| `d3-localization` | `egovio/localization:master-ce7a0b0` | 8108 | up 7 minutes |
| `d3-employee` | `egovio/employee:master-ce7a0b0` | 8109 | up 5 minutes |
| `d3-url-shortener` | `egovio/url-shortener:master-ce7a0b0` | 8110 | up 5 minutes |

All three were added to the **existing `digit3` compose project** (`/root/digit3/digit3/deploy/local/docker-compose.yml`),
inserted before the top-level `volumes:` key. Their migration containers all exited 0. **Compose
backups are in the same directory and still present:** `docker-compose.yml.pre-localization.bak`
(27,096 bytes) and `docker-compose.yml.pre-emp-urls.bak` (28,431 bytes), alongside the older
`docker-compose.yml.orig`.

**Probe data left behind**, declared because it is small and someone will find it: **3 rows** in the
v3 `localisation` table (`CCRS_PROBE_TEST`, `CCRS_PROBE_OTHER`, `CCRS_PROBE_MZ`), **1 row** in
`url_config`, **1 row** in `url_shortener` — all three counts re-confirmed today. **No employee row
was created** (writes need a Keycloak bearer token).

**Nothing else was changed, and this was checked, not assumed:**

- **No Kong routes were added.** The three new services are reachable only on their host ports.
- **CCRS routing is exactly as the hybrid run left it** — `EGOV_IDGEN_HOST`, `EGOV_BOUNDARY_HOST` and
  `EGOV_WORKFLOW_HOST` on `http://hybrid-shim:9100/`; `EGOV_USER_HOST` on
  `http://egov-user-proxy:8107/` (reverted, 2.x); MDMS, localization, HRMS, url-shortener and
  enc-service all still on their 2.x hosts.
- **The 2.x stack is untouched:** every `digit-*` and `egov-*` container shows 3 days' uptime
  (`digit-pgr-services-1` 3 hours, from its last deliberate restart); `hybrid-shim` 2 hours; the 17
  pre-existing `d3-*` containers 6 hours.
- 2.x row counts are unchanged from the data study: 55 complaints, 96 process instances, 20 users,
  121 role grants, 7,739 messages, 4,856 MDMS rows, 2,435 audit rows, **0 filestore rows**, both
  MinIO buckets 4.0K / 0 objects. `seq_eg_pgr_id` has moved 5,507 → **5,518**, which is gatus, not
  CCRS — the 2.x allocator is still climbing.
- `gaps.jsonl` is **1,042 lines**; `/opt/hybrid-shim/runs/` holds **50** artefacts.
- **egov-bomet (62.238.3.196) was never contacted by any part of this work.**

---

## 7. What cannot be sized on this data

State these as open, not as risks to be managed later. Each one is a prerequisite for a plan, not an
optimisation of one.

1. **Neither target tenant has been characterised on its own data.** Everything known about Bomet and
   Nairobi here comes from CCRS's committed config and test expectations, not from their databases.
   **Sizing either requires a read-only pass on its own database.**
2. **The master-data volume is an order of magnitude past the lab, and that is the only real-tenant
   number available.** `RAINMAKER-PGR.ComplaintHierarchy` is **249 rows at `ke`** (76 categories +
   173 leaves) and 138 at `ke.ige`, against **8** here. Nothing else about `ke` was measured.
3. **Attachments are completely unmeasured.** Zero objects on both stacks; Bomet's own expectations
   file requires attachment rendering. **The filestore migration is untested, and its volume is
   unknown.**
4. **Nothing is known about v3 under load.** No concurrency testing at all; the 100-row page cap was
   never exercised at a real inbox size.
5. **The async half of CCRS was never tested and is not testable by this method** (M11).
6. **The tenancy shape of the targets differs from the lab and from each other.** Bomet is a **flat
   single tenant `ke`** — and `ke.bomet` still exists in its database as legacy from a two-level
   bring-up that was later collapsed. **That flattening already went wrong in production**:
   `BOMET_ADMIN`'s HRMS record lives at `ke.bomet`, **HRMS does not fall back to a parent tenant**, so
   that account resolves no department and its ASSIGN is refused with `DEPARTMENT_NOT_FOUND`,
   cascading into ~22 skipped tests. *A flattening was performed under 2.x and it left orphaned rows
   that still break the product a year later.* **That is the best available evidence for what
   flattening costs.** Nairobi is the genuinely two-level case (`ke.testcity` and `ke.mombasa`
   parented to `ke.nairobi`) and is the one that actually exercises the sub-tenant questions.
7. **Whether `d3-pgr` is the intended destination** (M1 / §5.4). This single answer reshapes most of
   the work inventory.
8. **Whether v3's intended MDMS covers CCRS's masters is undetermined and undeterminable from this
   box** — there is no working v3 MDMS to ask (M10).

As bytes, this migration is an afternoon: the whole 2.x database is **23 MB** and about **15,700 rows
are worth moving**. The volume is not the work. **The work is that there is nothing to move most of
it into** — nine of the eleven v3 destination tables are empty right now, the two that are not hold
5 and 7 rows that were typed in by hand, and the platform says so itself:

> *"These changes require migration from any 2.9 LTS deployment. **There is no in-place upgrade
> path.**"* (release notes, line 38)
> *"Migrating from 2.9 LTS to 3.0 is a significant engineering effort. **There is no automated
> migration tool.**"* (line 469)
> *"**A detailed migration playbook per service will be published separately.**"* (line 481) — **it
> has not been.**

---

## Appendix — evidence index

| artefact | location |
|---|---|
| hands-on hybrid findings (idgen, boundary, workflow, user) | `docs/superpowers/specs/2026-09-20-ccrs-digit3-hybrid-findings.md` |
| append-only gap log, 1,042 lines / 95 distinct fields | `/opt/hybrid-shim/gaps.jsonl` on egov-subhashini — **last-entry rule applies** |
| 50 lifecycle / inbox run artefacts | `/opt/hybrid-shim/runs/` |
| `lifecycle.sh`, `compare.sh`, `inbox-check.sh` | `/opt/hybrid-shim/baseline/` |
| rollback compose files, per task | `/opt/hybrid-shim/docker-compose.egov-digit.yaml.task{3,4,5,6}-orig` |
| v3 compose (incl. the `d3-pgr` entry at line 709) | `/root/digit3/digit3/deploy/local/docker-compose.yml` on egov-subhashini |
| v3 service source (17 dirs, no `pgr`, no `mdms`) | `/root/digitnxt-new/digitnxt/digit3/src/services/` on egov-server |
| v3 specs (16 files, no `pgr.yaml`) | `/root/digitnxt-new/digitnxt/digit-specs/v3.0.0/` on egov-server |
| Bomet's measured shape | `tests/integration-tests/deploy/expectations/bomet.json` |
| Nairobi's tenant config | `local-setup/ansible/nairobi-mdms/mdms/data/ke/nairobi/tenant/tenants.json` |

Reproducing any measurement: 2.x `docker exec docker-postgres psql -U egov -d egov`; v3
`docker exec d3-postgres psql -U postgres -d postgres`; counts are `count(*)`, never `n_live_tup`.
All verification for this document was read-only. **egov-bomet (62.238.3.196) was never touched.**
