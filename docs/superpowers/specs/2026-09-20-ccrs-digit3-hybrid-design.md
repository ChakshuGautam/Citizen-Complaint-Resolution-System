# CCRS × DIGIT 3.0 Hybrid — Design

**Date:** 2026-09-20
**Status:** Approved for planning
**Goal:** Prove the migration path. Find out, by running it, which CCRS dependencies can be served by DIGIT 3.0 services today and which cannot — and record the field-level gaps.

## Background

CCRS runs on DIGIT 2.12. DIGIT 3.0 is a replatform, not an upgrade: its release notes state it is not backward compatible with 2.9 and that no in-place upgrade or automated migration tool exists. A review of the 2026-09-19 `digitnxt` drop found breaking changes in tenancy (sub-tenants removed, schema per tenant), identity (Keycloak-only), API shape (`RequestInfo` body → headers, REST verbs), and the write path (persister and indexer removed).

There is also **no usable DIGIT 3 complaints service**. The only PGR build, `sruj307/pgrown30:pgr-stable-latest`, is arm64-only and will not run on x86; `examples/pgr` is a tutorial skeleton covering roughly 15% of CCRS's complaint surface.

Therefore the hybrid keeps CCRS's own `pgr-services` and re-points its *dependencies* at v3, one at a time.

### Environments

| Host | Role |
|---|---|
| egov-subhashini (37.27.254.159) | Build and prove here. Runs CCRS 2.12 (39 containers) and, since 2026-09-20, DIGIT 3.0 as compose project `digit3` (Kong on 8000, container names prefixed `d3-`). |
| egov-bomet (62.238.3.196) | The environment this design ultimately targets. **Not touched by this work.** Live Bomet CCRS: 63 containers, 9.3 GB RAM free, disk 80% full. |

## Constraints discovered

1. **Hosts are config; shapes are not.** All ten outbound hosts in `pgr-services` are environment variables (`EGOV_WORKFLOW_HOST`, `EGOV_IDGEN_HOST`, …), and Spring relaxed binding makes the path properties settable too. But every HTTP call funnels through `ServiceRequestRepository.java:34`, which is POST-only, wraps `RequestInfo` in the body, and appends search criteria as query strings. DIGIT 3 uses `GET /v3/...` with `X-Tenant-ID` / `X-User-ID` headers. Config alone moves the traffic and then fails on shape, for every consumer at once.
2. **The local v3 stack has no auth enforcement.** Kong has 9 routes and zero plugins — no `dynamic-jwt`, no `keycloak-rbac`. Calls succeed with plain headers. This is what makes a cheap proof possible; production would enable those plugins, which is a later question.
3. **`notification` and `accesscontrol` run but are not routed** through the v3 Kong.
4. **Kafka paths have no v3 counterpart.** Persister (`save-pgr-request`), inbox/indexer, escalation events and the novu-bridge domain events have nothing to link to.

## Architecture

A single stateless shim process translates 2.x calls into v3 calls.

```
CCRS pgr-services ──POST /egov-workflow-v2/... (RequestInfo body)──▶ hybrid-shim :9100
                                                                          │ translate
                                                                          ▼
                                                            v3 Kong :8000 (X-Tenant-ID, X-User-ID)
                                                                          │
                                                        ◀── 2.x-shaped response + ResponseInfo ──┘
```

**One shim, not one per dependency.** CCRS's 2.x paths are already distinct per service (`/egov-idgen/id/_generate`, `/localization/messages/v1/_search`, `/boundary-service/boundary/_search`, `/egov-workflow-v2/egov-wf/process/_transition`), so a single process routes on path. Each `EGOV_*_HOST` we switch points at the same base URL, `http://hybrid-shim:9100`.

This follows a pattern the CCRS stack already uses: `egov-user-proxy` and `egov-workflow-proxy` are nginx containers sitting in front of services today.

### Components

| Unit | Purpose | Depends on |
|---|---|---|
| `router` | Maps an inbound 2.x path to a translator. Unknown path → 502 plus a gap-log entry. | — |
| `translator/<dep>` | One module per dependency. Pure functions: 2.x request → v3 request; v3 response → 2.x response. | — |
| `context` | Extracts `RequestInfo.userInfo.uuid` → `X-User-ID`, `tenantId` → `X-Tenant-ID`. Tenant mapping (`ke.bomet` → flat v3 tenant) lives here. | — |
| `gaplog` | Appends a structured record whenever a field is dropped, defaulted or faked. | — |
| `client` | Calls v3 Kong. Timeouts, no retries. | v3 Kong |

Translators are pure and individually testable: given a captured CCRS request, assert the exact v3 call produced. The router and client are the only parts doing I/O.

**Deployment:** compose project `hybrid` on subhashini, attached to both the CCRS and `digit3` networks. No database, no volumes.

### The gap log is the deliverable

Every translation records what it could not carry across: a field v3 has no home for, a default invented to satisfy a required v3 field, a response key faked to keep CCRS deserialising. At the end, that log is the field-level requirements list for a real migration — more useful than the running system, which is throwaway.

## Scope

**Switching, in order (easiest first, so failures are cheap):**

1. **idgen** — `POST /egov-idgen/id/_generate` → `/idgen/v3/...`. Smallest surface; proves the shim end to end.
2. **localization** — data maps 1:1 between 2.x and v3; only the endpoint differs.
3. **boundary** — concepts already align (hierarchy, relationship); 2.x sends a bare `RequestInfo` body with query-string `codes`.
4. **workflow** — the real test. CCRS's roles, SLA and escalation live here, and v3 requires process definitions to be registered through an API instead of seeded from MDMS JSON.
5. **individual / user** — hardest: drags in Keycloak identity and per-tenant realms.

**Staying on 2.x by design:**

| Dependency | Why |
|---|---|
| HRMS | v3 `employee` has a single department and no `reportingTo`, so CCRS escalation up the supervisor chain cannot be expressed. |
| MDMS | `pgr-services` builds v1 `MdmsCriteriaReq` objects; v3 MDMS ownership is unsettled (no service in `digit3/src`, and the spec `/mdms/v3` disagrees with the client `/mdms-v2/v2`). |
| enc-service | v3 encrypts inside the Individual service via Vault; there is no standalone equivalent. |
| url-shortening | v3 has a `url-shortener` service, but it is not routed through Kong. |
| Kafka: persister, inbox/indexer, escalation, novu-bridge | No v3 counterpart. Persister and indexer were removed outright. |

Out of scope: the CCRS UI, bomet, any data migration, and enabling v3 auth plugins.

## Verification

**Step one is a 2.x baseline.** Run create → assign → resolve against subhashini's existing CCRS and record every response. If that lifecycle does not pass, fix it before any switching — this is a task in the plan, not an assumption.

Then, per dependency:

1. Write translator tests from captured real requests (tests before translator).
2. Switch the one `EGOV_*_HOST` to the shim; restart `pgr-services`.
3. Re-run the lifecycle; diff against the baseline.
4. Green → keep it and move to the next. Red → record the failing call in the gap log, revert that one variable, and continue with the next dependency.

A dependency that fails is a result, not a blocker: the point is to learn where 2.x and v3 diverge.

## Error handling

- **Shim cannot translate** (unknown path, unmappable field): 502 with a gap-log entry, never a silent default. Invented defaults are always logged.
- **v3 returns an error:** pass the status through, wrapped in a 2.x-shaped error body so CCRS behaves as it would against 2.x.
- **v3 unreachable:** fail fast, no retries. CCRS's existing timeouts apply.

## Rollback

Point the environment variable back at the 2.x host and restart `pgr-services`. The shim is stateless and writes nothing. Bomet is never touched.

## Open questions

- Tenant mapping: what `ke.bomet` becomes in v3's flat tenancy — decided in `context` when we reach workflow.
- Whether v3 workflow can express CCRS's role-guarded actions (ESCALATE, SLA_ESCALATE, COMMENT) at all. Expected to surface at step 4.
