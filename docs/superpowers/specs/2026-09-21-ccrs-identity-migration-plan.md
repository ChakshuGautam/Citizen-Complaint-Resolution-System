# CCRS identity migration, 2.12 → 3.0: a costed work breakdown

**Date:** 2026-09-21 · **Audience:** whoever decides to fund this and when to schedule it
**Scope:** users, roles, authentication only. Not complaints, not workflow, not MDMS.
**Evidence:** every number below was re-counted today against egov-server
(`/root/code/Citizen-Complaint-Resolution-System`, branch `develop`) and the live lab box
egov-subhashini, read-only. Where a number could not be counted, this document says
**"not estimable without X"** rather than inventing one.

**Prior analysis this builds on (and does not repeat):**
`docs/superpowers/specs/2026-09-20-ccrs-digit3-migration-requirements.md` (W11, W13, W14, W25, W26,
§3 ordering) and `2026-09-20-ccrs-digit3-hybrid-findings.md` §6. **§9 of this document lists seven
places where re-counting disagreed with those documents.** Three of them change the cost.

---

## Headline

| | days |
|---|---|
| **CCRS-side, schedulable today** | **optimistic 45 · likely 67 · pessimistic 129** |
| **Blocked on DIGIT** | **7 days identifiable, plus 3 items not estimable at all** |

**Critical path (48 of the 67 likely days):**
A2 map → A3 reconciliation → C1 user ETL → C3 PII re-encryption → D1 identity-bff inversion →
D2 Kong → E1 UI adapters → E2 kc-parity.

**The point of no return is not the data migration.** It is D2 going live — the moment Kong stops
accepting 2.x opaque tokens and password resets go out. Everything up to and including C3 is
reversible *provided the 2.x encryption keys are escrowed and the 2.x stack stays up*.

**Fund A1 first, on its own.** Two days of read-only queries against Bomet's and Nairobi's databases.
Three of the thirteen tasks below cannot be honestly estimated until it is done, and the one
real-tenant number that exists is **31× the lab** (`ComplaintHierarchy`: 249 rows at `ke` vs 8 here).
It is the highest-leverage two days in this plan.

---

## 1. The task list

Each task states: what it is · what it touches · what it depends on · what "done" means as an
observable test · days, with the basis.

### Phase A — measure and instrument (nothing else should start first)

---

**A1 · Characterise Bomet (`ke`) and Nairobi identity on their own databases** — **2 days**

*Touches:* no repo files. Read-only SQL against two production databases.

*Depends on:* an organisational access grant. **egov-bomet (62.238.3.196) is explicitly out of bounds
to this workstream and was not contacted.** This is a permission dependency, not a technical one.

*Done:* a committed script plus its output, giving per tenant: `eg_user` count by `type`,
`eg_userrole_v1` grant count, distinct `(role_code, role_tenantid)` pairs, cross-tenant grant count,
distinct `accountid` on `eg_pgr_service_v2`, and `eg_enc_symmetric_keys` count — the same six queries
this document ran against the lab.

*Basis:* the six queries already exist; this document ran them in one sitting. The two days are
packaging, access, and reconciling two result sets — not query authorship. **Calendar time is not the
estimate: access approval is unbounded.**

---

**A2 · The durable `2.x uuid → (realm, individual id, keycloak sub)` map (W14)** — **2 days**

*Touches:* one new Flyway migration + a small DAO. New component; nothing in either stack holds this.

*Depends on:* nothing. **Schedulable today.**

*Done:* the table exists with a unique constraint on the source uuid *and* on
`(realm, keycloak_sub)`; a seed run over the lab's 20 users populates 20 rows; **running the seed a
second time changes 0 rows.**

*Basis:* one table of ~8 columns and two unique indexes, with 100+ Flyway precedents already in
`backend/pgr-services/src/main/resources/db/migration/main/`. Low novelty. The map is **mandatory,
not optional**, because v3 silently ignores a caller-supplied `id` on create and mints its own.

---

**A3 · Reconciliation instrument (W27)** — **4 days**

*Touches:* new component, run after **every** migration step.

*Depends on:* A2 (it asserts the map).

*Done:* one command prints, per tenant, `eg_user` / `individual_v3` / `user_entity` counts and
PASS/FAIL on the map being **total and injective**. **The acceptance test is a negative one: run
against the lab as it stands today it must report FAIL** — 7 individuals in `individual_v3`, 1
Keycloak user (`admin`, realm `master`), 0 tenant realms, 0 map rows. *If it passes on today's lab,
the instrument is wrong.*

*Basis:* 6 tenants × 3 stores = **18 counts** spanning two Postgres instances and Keycloak's own
store, ~300 lines, idempotent, emitting a diffable artifact. Grounded in the fact that producing the
same 18 numbers once, by hand, took this session three psql sessions and two rounds of quoting fixes.

---

### Phase B — realms and role vocabulary

---

**B1 · The role-vocabulary decision gate (W1)** — **3 days** (Option A) · Option B not fundable

*Touches:* `realm_config.json` (Option A), or 85 config files and 135 Java sites (Option B).

*Depends on:* nothing technical. It is a decision, and **it has a right answer.**

**Measured, so the decision is informed:**

| | Option A: mint CCRS's 12 codes into every realm | Option B: rewrite CCRS to v3's vocabulary |
|---|---|---|
| what changes | `realm_config.json` role list: 75 → 87; re-provision 6 realms | every reference to `GRO`/`DGRO`/`PGR_LME`/`CSR`/`SUPERVISOR`/… |
| measured scope | 12 names × 6 realms = **72 role creations**, one admin API call each, from one template edit | **135 references in backend Java** (GRO 55, PGR_LME 49, SUPERVISOR 24, AUTO_ESCALATE 7, DGRO 6, CSR 6), 6 in the UIs, and **2,157 references across 85 JSON/YAML config files** in 15 areas — MDMS role-action masters, Nairobi tenant config (20 files), default-data-handler (36), Helm charts (9), integration-test expectations |
| days | **3** | **not estimable below 30, and should not be funded** |

The overlap between CCRS's 15 role codes and the v3 realm template's 75 roles is **exactly 3** —
`CITIZEN`, `EMPLOYEE`, `SUPERUSER`. The 12 missing are `GRO`, `DGRO`, `CSR`, `PGR_LME`,
`PGR_VIEWER`, `SUPERVISOR`, `AUTO_ESCALATE`, `ACCOUNT_ADMIN`, `MDMS_ADMIN`, `LOC_ADMIN`, `CFC`,
`INTERNAL_MICROSERVICE_ROLE`.

Option B is not a rename. `GRO`/`DGRO`/`PGR_LME` encode an escalation ladder that v3's
`COMPLAINTS_ADMIN`/`RESOLVER`/`ASSIGNER` do not express. **Recommendation: Option A.**

*Done:* a token minted from a tenant realm carries the 12 codes in `realm_access.roles` — which is
the only place the v3 workflow build reads action roles from.

*Basis for the 3 days:* the template already carries 75 roles, so the mechanism exists; the work is
the edit, re-provisioning 6 realms, and verifying the claim actually lands in a minted token.

---

**B2 · Provision the CITIZEN realm and the per-tenant realms** — **4 days, conditionally**

*Touches:* Keycloak realm provisioning; `realm_config.json`.

*Depends on:* B1.

*Today:* **1 realm exists (`master`), holding 32 roles — all of them Keycloak's own built-ins
(`admin`, `create-client`, `view-profile`, …) — and 1 user.** Nothing has ever been provisioned.
6 tenants on the lab, in two trees: `pg`, `pg.citya`, `pg.cityb`, `pg.citest`, `mz`, `mz.maputo`.

*Done:* 6 realms exist; a token from each carries the CCRS role codes; `d3-individual` (running with
`KEYCLOAK_ENABLED=true`) resolves a user in a realm other than `master`.

*Basis:* 6 realms from one template, plus the CITIZEN realm decision. **But:** whether sub-tenants
(`pg.citya`, `pg.cityb`, `pg.citest`, `mz.maputo`) get their own realms or share the parent's
**changes this task's size by 2× and cannot be answered from the lab.** 4 days assumes they share.

*Two facts that make mistakes expensive:* a realm's name **is** the tenant `code` and is
**immutable**, and a realm is destroyed with its tenant. A mistyped tenant code is not renameable.

---

**B3 · The citizen registration and login path** — **5 days · BLOCKED ON DIGIT**

*Blocked on:* the `otp` service. It is **not in the v3 compose file, has no image deployed**, and
`d3-account` points at `localhost:8107/otp`. Citizen login cannot be built or tested.

*Also:* `identity-bff` is employee-only today — `MANAGED_USER_TYPE = "EMPLOYEE"`
(`managed-account-service.ts:35`), and CCRS's citizens are exactly the population that fails with
`INVALID_ACCOUNTID`. The lab holds **1 CITIZEN user against 17 EMPLOYEE**, and its citizen auth
**accepts any password** (OTP mode, enforcement off). *The lab cannot test this path even once otp
exists.*

*The 5 days is the CCRS-side work once otp is available. The OTP contract itself is not estimable.*

---

### Phase C — move the people

---

**C1 · ETL: `eg_user` → `individual_v3` + a Keycloak user per realm** — **5 days**

*Touches:* new ETL; writes to `individual_v3`, `user_entity`, and A2's map.

*Depends on:* A2, A3, B2.

*Volume:* **20 rows / 6 tenants** (1 CITIZEN, 17 EMPLOYEE, 2 SYSTEM). A person in three tenants fans
out to three realm users with nothing linking them — that is precisely what A2's map is for.

*Four measured v3 behaviours that cost the time, none of which is about row count:*
- a caller-supplied `id` on create is **silently ignored** (sent `aaaaaaaa-…`, got `629b9122-…`, HTTP 201)
- `?userId=` is **accepted and ignored**, returning the whole tenant with HTTP 200 — so you cannot
  read back what you wrote by 2.x uuid
- `gender` is **mandatory** in the deployed build though the spec says optional; CCRS has no such field
- `nameRegex` defaults to `^[A-Za-z ]+$`, rejecting initials, dots, hyphens and digits that 2.x
  accepts — **check the 20 names against it before assuming zero rejects**

*Done:* 20 individuals and 20 Keycloak users exist; A2's map holds 20 rows; A3 reports PASS; and
**re-running the ETL changes 0 rows.**

*Basis:* the row count is trivial and is not the work. The cost is entirely idempotency against a
registry that ignores the id you give it and cannot be queried by the key you need.
**This does not scale from here by row count — Bomet and Nairobi are unmeasured (A1).**

---

**C2 · Role-grant migration** — **4 days for 113 of 121 · the other 8 not estimable**

*Touches:* new ETL writing realm-role mappings.

*Depends on:* B1, B2, C1.

**This is where the sizing in the existing documents is wrong.** W11 sizes it as "121 grants / 15 role
codes". Re-counted:

- `eg_userrole_v1` carries **two** tenant columns — `role_tenantid` **and** `user_tenantid`.
- The number of realm roles to create is not 15 but **61 distinct `(role_code, role_tenantid)`
  pairs**, spread across 6 realms.
- **8 of the 121 grants are cross-tenant** (`role_tenantid` = `pg`, `user_tenantid` = `pg.citest`).

A Keycloak realm role lives in exactly one realm, and a user lives in one realm. **Those 8 grants have
no representation in a per-tenant-realm model.** They need a designed answer — cross-realm role
mapping, a shared realm, or an accepted capability loss — before the ETL can be written.

*Done:* the 113 same-tenant grants land as realm-role mappings; the 8 cross-tenant grants land
wherever the design says; A3 asserts 121 = 113 + 8 with none silently dropped.

*Basis:* 4 days for the 113. **The 8 are not estimable until the cross-realm design decision is made**
(≥3 days once it is).

---

**C3 · PII re-encryption into Vault transit (W12, I3)** — **5 days · IRREVERSIBLE ×6**

*Touches:* `eg_user` PII columns; `eg_enc_symmetric_keys`; Vault transit.

*Depends on:* C1.

*Volume, re-counted:* **52 populated PII values across 3 columns** — 20 `mobilenumber`, 20 `name`,
12 `emailid`. `aadhaarnumber`, `pan` and `altcontactnumber` are **all 0**. (The existing doc's
"3 PII columns × 20 rows" = 60 overstates it slightly; the column *set* is the correction that
matters, not the count.) **6 symmetric keys, one per tenant.**

*The actual work is `hashedmobilenumber`.* 2.x mobile ciphertext is **deterministic** — that is how
2.x searches by mobile without decrypting. Vault transit is not. The hash must be recomputed by the
migration, and CCRS's `upsertUser` treats mobile as identity.

*Done:* every migrated individual decrypts to the same mobile/name/email as its 2.x source, proven
row by row in a scratch file that is then destroyed; search-by-mobile returns the same person on both
stacks.

*Irreversible:* per tenant, at the moment its `eg_enc_symmetric_keys` row is retired. **Six
independent points of no return.** Keeping the 2.x key escrowed is the only thing that makes rollback
possible at all.

*Basis:* 6 key retirements × (decrypt, re-encrypt, recompute hash, verify, escrow). The hash
recomputation is genuinely novel work. Mounting Vault transit itself is hours (W5; already mounted on
the lab as `transit_eae1d11a`).

---

### Phase D — invert the bridge

---

**D1 · Invert identity-bff (W25)** — **15 days · the single largest item**

*Touches:* `backend/identity-bff/` — **4,014 lines across 31 source files**.

*Depends on:* C1 (it needs individuals to point at).

**The inversion, stated once:** today identity-bff authenticates against Keycloak and then mints a
2.x `egov-user` **opaque** token via `grant_type=password` (`digit-user-client.ts:112,119`), writing
through `/users/_createnovalidate` and `/users/_updatenovalidate` (lines 176, 180). Its design
principle is that the Keycloak JWT **never** reaches the API path. v3 reads action roles **only** from
a Bearer JWT. You invert the principle: the JWT becomes the thing you forward, and the 2.x mint is
deleted. It terminates at precisely the component v3 removes.

**The existing documents under-scope this.** W25 says `managed-accounts/` is discarded and
`authentication/`, `sessions/`, `organizations/`, `reconciliation/` are reusable. Re-counted:

| | measured |
|---|---|
| `managed-accounts/` — discarded | **680 lines, 3 files** (`managed-account-service.ts` 381, `digit-user-client.ts` 203, `digit-admin-session.ts` 96) |
| references to it from elsewhere | **17, from 10 files across 6 modules** — `sessions`, `control-plane`, `access-context`, `onboarding`, `organizations` **and** `reconciliation` |
| genuinely reusable as-is | `authentication/token-verifier.ts` (110 lines, already multi-realm: 12 realm references, caches JWKS per realm) and `integrations/keycloak/admin-session.ts` (46 lines) |

**Every module called "reusable" imports the module being discarded.** `reconciliation/subject-sync.ts`
alone imports 5 symbols from `managed-account-service.js`. "Reusable" means *survives with its call
sites rewritten*, not *untouched*.

*Done:* `grep -c "grant_type=password" src/` returns **0**; a Keycloak JWT reaches pgr-services and
its roles are read from `realm_access.roles`; the test suite is re-cut and green.

*Basis:* 680 lines deleted · 10 files re-pointed across 6 modules · 17 call sites · a new v3
individual/employee client replacing 299 lines of 2.x client · and **46 existing tests across 5 files
/ 1,372 lines** re-cut (the 14 in `managed-digit-users.test.ts` test the discarded module directly
and are deleted; the 10 in `token-verifier.test.ts` largely survive). Not deployed on subhashini —
this is not a bridge you switch on, it is one you finish, redirect and extend.

---

**D2 · Kong's auth plugin** — **6 days · THE POINT OF NO RETURN**

*Touches:* `local-setup/kong/kong.yml` — **1,180 lines, 37 services, 45 routes**.

*Depends on:* D1.

*What is there:* a **global** `pre-function` Lua block at line 97 that resolves `authToken` → `userInfo`
against `egov-user`, mirroring the Spring gateway's `AuthCheckFilterHelper`, with a **35-entry
AUTH_OPTIONAL whitelist** (open ∪ mixed). Being global, it applies to **all 45 routes**, and **today it
rejects a Keycloak JWT before anything downstream can use it.**

This is also why identity does not move with `EGOV_USER_HOST`: `POST /user/_details?access_token=`
(205 calls) and `POST /user/oauth/token` (120) come from kong-gateway through **its own** upstream.

*Done:* a Keycloak JWT on a protected route is verified against the realm's JWKS and passed through
with roles intact; the 35 AUTH_OPTIONAL entries still answer anonymously; no route regresses, proven
by the re-cut parity baseline (E2).

*Basis:* one global Lua block rewritten from opaque-token introspection to JWKS verification · 35
whitelist entries re-verified · 45 routes regression-tested · and a **second** `pre-function` at line
640 (the `egov-location` → `boundary-service` adapter) that must keep working. **The risk is Lua in a
declarative Kong config with no unit-test harness anywhere in the repo — not the line count.**

*Irreversible:* this is the cutover. 2.x validates a token live; v3 validates a signature. There is no
dual-stack period. **20 bcrypt password hashes cannot be carried over** — Keycloak can import bcrypt
via a custom hash provider, v3's realm template ships none, and `d3-keycloak`'s provider directory
contains no such jar. **Assume every user resets:** moot for the 1 citizen under OTP, a coordinated
reset for the 17 employees.

---

### Phase E — the UIs

---

**E1 · The two UI adapters (W26)** — **6 days**

*Touches:* `digit-ui-esbuild/packages/libraries/src/services/auth/KeycloakAuthAdapter.js`
(**331 lines, 11 exported/async functions**) and `digit-ui-v2/src/api/keycloak.ts`
(**345 lines, 22**). Both target the retired `/kc` exchange (Kong `/kc/*`, `strip_path: true`).

*Depends on:* D2.

*Done:* both SPAs log in against a tenant realm and reach a protected endpoint with a JWT; `grep -c
'/kc'` returns 0 in both; the parity run is green.

*Basis:* 676 lines, 33 entry points, **two different stacks** — an esbuild library package and a v2
SPA. They are separate codebases; the work does not halve. 3 days each.

---

**E2 · Re-cut the kc-parity baseline** — **5 days**

*Touches:* `local-setup/tests/e2e/kc-parity/` — 9 harness files (`capture.js` 333 lines, `diff.js`,
`pgr-ui-lifecycle.spec.ts` with **3 Playwright tests**, 6 probe scripts, 2 shell runners) and **30
artifacts in `out/`**.

*Depends on:* E1.

**Why it must be re-cut and not merely re-run — and this is the important part.** `diff.js` classifies
divergence by **credential kind**. It treats `jwt` vs `uuid` on a `/kc/*` call as *by design*, and
normalises `jwt` → `uuid` before comparing (`diff.js:119`). **Under v3 every call is `jwt`, so that
normalisation makes the comparator vacuous — it would report green on a completely broken build.**
The comparator is the deliverable; the baselines are a by-product.

*The baselines to re-capture:* `boot-baseline.json` (**9 captured calls**), `inbox-baseline.json`
(**30**), `citizen-kc.json` (**28**), `audit-baseline.json` (**119 entries**) — 186 recorded
calls/entries in total.

*Done, in this order:* (1) `diff.js` no longer contains the `jwt`→`uuid` substitution; (2) **a
deliberately broken token path makes the parity run FAIL** — the negative test that proves the
comparator still discriminates; (3) the 4 baselines are re-captured; (4) the 3 lifecycle tests are
green.

*Basis:* rewrite one ~250-line comparator · re-capture 186 calls across 4 baselines · re-green 3
Playwright tests · write the negative test.

---

### Phase F — the complaints

---

**F1 · Rewrite `accountid` on every complaint (W13/W19)** — **6 days at lab volume · DESTRUCTIVE**

*Touches:* **far more than the existing documents say.** W19 scopes this as "five read sites, one
write site" across 5 named files. Re-counted:

| | measured |
|---|---|
| Java | **40 references across 13 files** — `UserService.java` **12**, `ServiceRequestValidator.java` **6**, `MigrationService.java` **5**, then `EnrichmentService`, `PGRRowMapper`, `PGRQueryBuilder`, `DashboardQueryBuilder`, `RequestSearchCriteria`, both `Service` models, `WorkflowService`, `PolicyInputBuilder`, `AccessPolicyRegistry`. **Eight of those 13 files are not named in W19.** |
| resources | **49 references across 17 files**, including **12 Flyway migrations** |
| **materialized views** | **4 are defined over `accountid`** — `pgr_mv_kpi`, `pgr_mv_monthly`, `complaint_events`, `complaint_facts`, confirmed against `pg_matviews.definition` on the live lab DB. **Costed nowhere in the existing documents.** |

Rewriting `accountid` invalidates all four MVs; they must be refreshed, and the escalation/dashboard
grain migrations that build them (`V20260608`, `V20260629`, `V20260708`, `V20260810`, `V20260915` —
5 references each) assume the column's meaning.

*Depends on:* A2 (the map), C1 (the individuals).

*Lab volume:* **55 complaints across 3 tenants, and exactly 1 distinct `accountid` in the whole
table.** One citizen. **The lab cannot exercise this task at all.**

*Done:* every complaint's `accountid` resolves through A2's map to a live individual — concretely,
`select count(*) from eg_pgr_service_v2 s left join <map> m on s.accountid_old = m.source_uuid where
m.individual_id is null` returns **0**; all 4 MVs refresh and their row counts match pre-migration.

*Irreversible* in place — **unless the old value is kept in a shadow column. Add the shadow column
first, in its own migration, before the rewrite.**

*Basis:* 13 files / 40 references (not 5/6) · 4 MVs · 1 shadow-column migration.
**Not estimable at real volume until A1.**

---

## 2. Critical path, order, and what runs in parallel

```
A1  Characterise Bomet + Nairobi  ──────────────────────────┐  (parallel throughout; gates F1's estimate)
                                                            │
A2 map (2) ─► A3 reconcile (4) ─┬─► C1 user ETL (5) ─┬─► C3 PII re-encrypt (5) ─┐
                                │                     │                          │
B1 decision (3) ─► B2 realms (4)┘                     ├─► C2 role grants (4)     │  (parallel to C3)
                                                      │                          │
                                                      └─► F1 accountid (6) ──────┤  (parallel to D1–E1)
                                                                                 │
                                                    D1 identity-bff (15) ◄───────┘
                                                            │
                                                    D2 Kong (6)   ══ POINT OF NO RETURN ══
                                                            │
                                                    E1 UI adapters (6)
                                                            │
                                                    E2 kc-parity (5)
```

**Critical path:** `A2 → A3 → C1 → C3 → D1 → D2 → E1 → E2` = **48 days of the 67.**
D1 alone is 15 of those 48. If anything is worth de-risking up front, it is D1 (see §5, risk 3).

**Runs in parallel:** A1 alongside everything (it gates only F1's *estimate*, not F1's start).
B1/B2 alongside A3. C2 alongside C3. **F1 alongside D1–E1**, once C1 lands — it needs only the map and
the individuals, not the new auth path.

**Irreversible steps, in the order they are reached:**

| step | the point of no return | reversible before it by |
|---|---|---|
| **B2** realm creation | realm name **is** the tenant code and is immutable; a realm dies with its tenant | deleting the realm — cheap, *if* the code was right |
| **C3** PII re-encryption | per tenant, when `eg_enc_symmetric_keys` is retired — **6 separate points** | **escrowing the 2.x key.** Destroying it destroys the 2.x data permanently |
| **F1** accountid rewrite | the first `UPDATE` | **the shadow column.** Without it, nothing |
| **D2** Kong cutover | **the day password resets go out** | nothing. This is the one |

Everything up to and including C3 is reversible *provided the 2.x keys are escrowed and the 2.x stack
stays up*. **The point of no return for the migration as a whole sits at D2, not at the data
migration.** That is the useful scheduling fact: the ETL work (A, B, C, F) can be rehearsed,
re-run and abandoned; the cutover (D2) cannot.

**Rehearse on a restored `pg_dump -Fc`, with `pg_restore --clean` proven.** It is the only rollback
that exists for any of the above.

---

## 3. The range

| | days | assumptions it rests on |
|---|---|---|
| **Optimistic** | **45** | one engineer already fluent in **both** CCRS's 2.x identity path and Keycloak realm administration · v3 unchanged from the build on egov-subhashini · no DIGIT-side fixes needed or wanted · scope is the **lab's 20 users / 6 tenants only** · sub-tenants share the parent's realm · the 8 cross-tenant grants resolved by fiat |
| **Likely** | **67** | the same engineer · A1 comes back showing Bomet/Nairobi similar in *shape* if not volume · one design round-trip each on the cross-tenant grants and the sub-tenant realm question · E2's comparator rewrite finds one real divergence |
| **Pessimistic** | **129** | the engineer is fluent in one stack, not both · A1 shows real tenants an order of magnitude past the lab (precedent: `ComplaintHierarchy` 249 rows at `ke` vs 8 here) · sub-tenants need their own realms, doubling B2 · Kong's Lua rewrite needs a test harness built first · **the JWT-signature fix lands mid-flight**, invalidating everything tested against the current build's `{"alg":"none"}` acceptance |

Per-task: A1 2 · A2 2 · A3 4 · B1 3 · B2 4 · C1 5 · C2 4 · C3 5 · D1 15 · D2 6 · E1 6 · E2 5 · F1 6
= **67 likely**.

At one engineer and a 5-day week, 67 days is **13–14 calendar weeks**, excluding A1's access approval,
the B1 decision meeting, and the coordinated password reset for 17 employees.

---

## 4. Dependencies on the DIGIT team — separated, because these are not schedulable

**CCRS-side work is schedulable today. This is not.** Costed separately so the first can be funded
without waiting on the second.

| # | what DIGIT must ship | what it blocks | CCRS days behind it |
|---|---|---|---|
| **1** | **An assignable individual id (M7)** — honour a caller-supplied `id` on create, or make `userId` a real filter. All three mappings were tried against the live service and all three failed | Nothing *blocked* — but this single answer **shrinks C1 and F1**, because the A2 map exists only to work around its absence | *reduces* ~4 |
| **2** | **Fix filters accepted and ignored (M8)** — `?userId=`, `?givenName=` and `?wibble=` all return the whole tenant with HTTP 200, while `individual.yaml` documents them as real | Nothing blocked, but until fixed **no CCRS code may trust an individual search result** and A3 is not optional | 0, but raises A3's floor |
| **3** | **The `otp` service** — not in the compose file, no image, while `d3-account` points at `localhost:8107/otp` | **B3 entirely.** Citizen login cannot be built or tested | **5** |
| **4** | **`reportingTo` / an org hierarchy (M3)** — `employee_v3` has no such column, and a grep for `reportingTo|reporting_to|supervisor` across all 16 v3 specs returns **zero** | CCRS's `SUPERVISOR` role (**24 Java references**) and the whole escalation ladder have no destination | **2** on the cheap branch (disable via `pgr.escalation.enabled`, accept the capability loss); **not estimable** on the expensive one (CCRS owns the supervisor edge) |
| **5** | **A working MDMS (M10)** — serves no endpoints, holds 0 rows; the jar is `egov-mdms-service-**test**-1.3.3-SNAPSHOT.jar` | The **role-action masters** defining what each of the 15 role codes may *do*. The authorisation half of identity has nowhere to land | **not estimable** |
| **6** | **A PGR service (M1)** — the only candidate is `sruj307/pgrown30:pgr-stable-latest`, a personal Docker Hub namespace, out of generation, never deployed | F1 rewrites `accountid` on a table that has no owning service in v3 | **not estimable** |
| **7** | **A verified JWT signature** — the current build honours `{"alg":"none"}` | Nothing today. **But a fixed build will verify, and anything built against current behaviour must be re-tested** | 0 now; a re-test later |

**Blocked-on-DIGIT total: 7 days identifiable (items 3 and 4's cheap branch), plus three items that
are not estimable at all** (5, 6, and item 4's expensive branch).

Ask DIGIT for **item 1 first**. It is the cheapest for them and it is the only one that makes CCRS's
number go *down*.

---

## 5. The risks that would blow the estimate

**1 · Bomet and Nairobi are unmeasured, and the one real-tenant number available is 31× the lab.**
Everything known about them comes from committed config and test expectations, not from their
databases. `RAINMAKER-PGR.ComplaintHierarchy` is **249 rows at `ke`** against **8** here. If user and
grant counts scale anything like that, C1, C2 and F1 are wrong by an order of magnitude — and F1 in
particular is untestable on the lab, where **all 55 complaints share exactly one `accountid`**.
**Cheap de-risk: A1. Two days of read-only queries, six of which are already written. Do it before
funding anything else.**

**2 · "Accepted and ignored" is the dominant failure mode of this v3 build, and it produces silent
partial success.** A caller-supplied `id`, a `?userId=` filter, `additionalDetails`, a state `type`,
an `assigner` — all accepted, all discarded, all HTTP 200. **The lab already proves the consequence:
7 individuals in `individual_v3` with `userid` populated, against 1 Keycloak user (`admin`, in
`master`) and 0 tenant realms.** Seven people who can never log in. Nothing errored; nothing was
logged. A re-run, a partial failure, or an off-by-one in the map yields duplicate people and
complaints reattached to the wrong citizen, with 200s everywhere.
**Cheap de-risk: A3, built *and proven to FAIL on today's known-bad lab state* before any data moves.
If A3 passes on the lab as it stands, A3 is wrong.** The evidence needed to rebuild a mapping after
the fact is exactly the evidence that was lost.

**3 · identity-bff's "reusable" modules do not compile without the module being discarded.**
17 references from 10 files across 6 modules point into the 680 lines that go away. If D1 is scoped
as "delete `managed-accounts/`, keep the rest" — which is how the existing analysis reads — it will
overrun, and D1 is 15 of the 48 critical-path days.
**Cheap de-risk: one afternoon. Delete `src/modules/managed-accounts/` on a scratch branch and run
`tsc`. The error count is the real scope of D1, measured rather than argued. I would not fund D1 at 15
days without that number.**

*Two more worth naming, cheaper to state than to discover:* Kong's Lua has **no test harness anywhere
in the repo** (de-risk: write one spike test against the existing plugin before touching it — half a
day); and `diff.js` normalises `jwt`→`uuid`, so **the parity harness would report green on a
completely broken v3 build** (de-risk: E2's negative test, which is half of E2 and should be done
first).

---

## 6. What this plan does *not* cover

Stated so the number is not mistaken for a total: complaints (blocked on M1), workflow (drain, do not
migrate — W15), MDMS (blocked on M10), audit (freeze and retain, never rewrite — the `integrityhash`
chain cannot be re-derived), attachments (**0 objects on both stacks; completely unmeasured**, and
Bomet's own expectations file requires attachment rendering), boundary and localisation reference data,
and the entire Kafka/async half of CCRS (M11), which is not HTTP and cannot be re-pointed by an
environment variable.

---

## 7. Verification appendix — every number in this document

Re-counted 2026-09-21, read-only. 2.x: `docker exec docker-postgres psql -U egov -d egov`.
v3: `docker exec d3-postgres psql -U postgres -d postgres`. Source: egov-server, branch `develop`.
**egov-bomet (62.238.3.196) was not contacted.**

| claim | value | how |
|---|---|---|
| users / tenants / grants / role codes | 20 / 6 / 121 / 15 | `eg_user`, `eg_userrole_v1` counts |
| user types | 1 CITIZEN, 17 EMPLOYEE, 2 SYSTEM | `eg_user group by tenantid, type` |
| cross-tenant grants | **8** of 121 (113 same-tenant) | `count(*) filter (where role_tenantid <> user_tenantid)` |
| distinct (role_code, role_tenantid) | **61** | `count(distinct (role_code, role_tenantid))` |
| populated PII | 20 mobile, 20 name, 12 email; 0 aadhaar/pan/alt | `count(col)` per column |
| symmetric keys | 6 | `eg_enc_symmetric_keys` |
| complaints / distinct accountid | 55 / **1** | `eg_pgr_service_v2`; per tenant pg 52, pg.citest 2, pg.citya 1 |
| MVs over accountid | **4** — `pgr_mv_kpi`, `pgr_mv_monthly`, `complaint_events`, `complaint_facts` | `pg_matviews where definition ilike '%accountid%'` |
| v3 individuals | 7, all tenant `pg`, all with `userid` | `individual_v3` |
| Keycloak realms / users / roles | **1** (`master`) / 1 / **32, all built-ins** | `realm`, `user_entity`, `keycloak_role` |
| realm template roles / CCRS overlap | **75 / exactly 3** (CITIZEN, EMPLOYEE, SUPERUSER); 12 missing | `digit3/src/services/account/src/main/resources/realm_config.json` |
| accountId in Java | **40 refs / 13 files** | `grep -rn` over `backend/pgr-services/src/main/java` |
| accountId in resources | 49 refs / 17 files, incl. 12 Flyway migrations | `grep -rc` over `src/main/resources` |
| role codes in Java / UI / config | 135 / 6 / **2,157 across 85 files** | `grep -rwE` |
| identity-bff | 4,014 lines / 31 files; `managed-accounts/` 680 / 3 | `find … -name '*.ts' \| xargs wc -l` |
| refs to managed-accounts | **17, from 10 files in 6 modules** | `grep -rc "managed-accounts" src/` |
| identity-bff tests | **46 across 5 files / 1,372 lines** | `grep -c "it(\|test("` |
| UI adapters | 331 lines / 11 fns; 345 lines / 22 fns | `wc -l`, `grep -c` |
| kong.yml | 1,180 lines, 37 services, 45 routes, 35-entry AUTH_OPTIONAL whitelist, 2 `pre-function` blocks (lines 97, 640) | `grep -c`, `sed -n` |
| kc-parity | 9 harness files, 30 artifacts, 3 Playwright tests; baselines 9 / 30 / 28 calls + 119 audit entries | `find`, `json.load` |

---

## 8. Corrections to the existing documents

Seven places where re-counting today disagreed with
`2026-09-20-ccrs-digit3-migration-requirements.md`. **Three change the cost.**

**8.1 — W19 under-scopes the `accountId` rewrite by ~2.5×.** It says "five read sites, one write site"
across 5 named files. Measured: **40 references across 13 Java files** (8 of them unnamed in W19) plus
**49 across 17 resource files including 12 Flyway migrations**. *Cost impact: F1 moves from ~2 days to
6.*

**8.2 — Four materialized views are defined over `accountid` and are costed nowhere.**
`pgr_mv_kpi`, `pgr_mv_monthly`, `complaint_events`, `complaint_facts`. Rewriting the column invalidates
all four. *Cost impact: included in F1's 6 days; it was in nobody's number before.*

**8.3 — W25 calls modules "reusable" that import the module being discarded.** `sessions`,
`organizations` and `reconciliation` — plus `access-context`, `control-plane` and `onboarding`, which
W25 does not mention at all — hold **17 references** into `managed-accounts/`.
`reconciliation/subject-sync.ts` alone imports 5 symbols from it. *Cost impact: D1 is 15 days, not the
smaller figure "reusable" implies. This is the largest single item in the plan and the correction that
matters most.*

**8.4 — W11's "121 grants / 15 role codes" is the wrong shape for a realm model.** `eg_userrole_v1`
carries **two** tenant columns. The realm-facing number is **61 distinct `(role_code, role_tenantid)`
pairs**, and **8 of the 121 grants are cross-tenant** — which **have no representation** in a
per-tenant-realm model and need a design decision before the ETL exists. *Cost impact: C2 splits into
4 estimable days and 8 grants that are not estimable.*

**8.5 — W12's PII surface is slightly smaller than stated.** "3 PII columns × 20 rows" = 60; measured
**52 populated values** (20 mobile, 20 name, 12 email), with `aadhaarnumber`, `pan` and
`altcontactnumber` all **0**. Minor, and it does not change C3's estimate — the `hashedmobilenumber`
recomputation is the work, not the row count.

**8.6 — W1's "~75 realm roles per realm, overlap is 3" is correct, but comes from a template, not a
live realm.** It is `realm_config.json`: 75 realm roles, overlap **exactly** CITIZEN/EMPLOYEE/SUPERUSER,
12 missing — all confirmed. Worth stating because the document reads as if measured live: **the running
Keycloak holds 32 roles, every one of them a Keycloak built-in, all in `master`, and no tenant realm
has ever been created.** The live overlap is **0**. The decision gate is real; the number behind it is
a template's.

**8.7 — Confirmed unchanged, re-verified today:** 20 users / 6 tenants / 121 grants / 15 role codes /
55 complaints / 6 symmetric keys / 7 v3 individuals / 1 Keycloak user. The existing documents' counts
hold. **New:** all 55 complaints share exactly **one** `accountid` — so the lab cannot exercise F1 at
all, and F1's estimate is contingent on A1.
