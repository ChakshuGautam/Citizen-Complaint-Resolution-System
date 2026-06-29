# PGR Config-Driven Notifications — Design

**Date:** 2026-06-29
**Status:** Design (approved direction; implementation plan to follow)
**Target deploy:** Bomet (`ke.bomet`, `bometfeedbackhub.digit.org`)
**Supersedes direction of:** PR #915 / MOZ_007 (no new `workflow-extension-service`; config in MDMS; all biz logic in PGR)

---

## 1. Context & goals

PGR hardcodes **who** is notified on each workflow transition, in three places:

- `NotificationService.process()` — enable gate `NOTIFICATION_ENABLE_FOR_STATUS.contains(action+"_"+status)` (line 74) + per-transition **employee-phone resolution** (lines 86–116).
- `NotificationService.getFinalMessage()` — **7 hardcoded `if (status && action)` blocks** (lines 186–500) that decide whether a citizen and/or employee message is built.
- `ComplaintDomainEventService.getStakeholders()` — **transition-agnostic** (citizen + every assignee, always).

SMS bodies live in `egov-localization` keyed `PGR_<ROLE>_<ACTION>_<STATUS>_SMS_MESSAGE` (`NotificationUtil.getCustomizedMsg()` line 92).

**Goal:** make routing (the "who") and content (the "what") **data-driven via MDMS, editable in the configurator**, with **all orchestration, rendering, and localization in PGR**. PGR pre-renders and publishes per-recipient events to Kafka; Novu becomes a thin **delivery + tracking** layer across SMS, email, and WhatsApp.

## 2. Locked decisions

| # | Decision |
|---|---|
| D1 | **No new microservice.** PGR is the only brain. |
| D2 | Config in **MDMS** (two masters), surfaced in the **configurator**. |
| D3 | Subscribers are a **closed relationship enum**: `CITIZEN`, `ASSIGNEE`, `CREATOR`, `PREVIOUS_ASSIGNEE` (extensible). NOT RBAC roles. |
| D4 | **PGR renders + localizes BEFORE Kafka.** Novu just delivers + tracks. |
| D5 | **Bring up Novu on Bomet** (Docker Compose, ported from `local-setup/`). All channels flow `PGR → Kafka → novu-bridge → Novu`. |
| D6 | Novu subscribers are **upserted (identify), not fetched** — idempotent by `subscriberId`. |
| D7 | **WhatsApp via Baileys** (free-form messages, no Twilio approved-template requirement). |
| D8 | Backward compatible: behind a feature flag, legacy path retained one release; Bomet seed reproduces current behavior exactly. |

## 3. Architecture

```
Workflow transition (PGR _update: fromState → action → toState)
        │
        ▼
PGR  ── the only brain (no new service) ───────────────────────────────────
  1. read config 1 (MDMS PGR.NotificationRouting):
        (businessService, fromState, action, toState) → [subscriberGroups], [channels]
  2. resolve each subscriberGroup → concrete user + contact
        CITIZEN/ASSIGNEE/CREATOR/PREVIOUS_ASSIGNEE
        via service data + workflow Process-Instance history
  3. read config 2 (MDMS PGR.NotificationTemplate):
        (audience, eventName, channel, locale) → body template
  4. RENDER + LOCALIZE fully here, BEFORE Kafka   (D4)
  5. publish ONE event per (recipient × channel) → complaints.domain.events
        { eventName, channel, subscriberId, contact{phone,email,name,locale},
          renderedBody, transactionId, data{...} }
        │
        ▼
Kafka → novu-bridge — thin delivery + tracking ONLY
  • upsert Novu subscriber (identify: phone, email, name, locale, data)   (D6)
  • trigger Novu with the pre-rendered body (NO template resolve, NO localization)
  • provider strategy per channel: SMS→gateway, EMAIL→email provider, WHATSAPP→Baileys
  • dispatch_log (idempotent on transactionId) + Novu's own delivery tracking
        │
        ▼
Novu → SMS gateway / Email provider / Baileys WhatsApp send-service
```

**The inversion vs PR #915 / current code:** novu-bridge **stops** resolving templates from config-service and **stops** localization — PGR owns both. novu-bridge only identifies subscribers and delivers pre-rendered content. This realizes the PR-body intent: "make it more pass-through, retain all biz logic inside PGR."

## 4. MDMS masters (config 1 & config 2)

### 4.1 `PGR.NotificationRouting` (config 1 — the "who")

One document per `businessService`, with a `transitions[]` list.

```jsonc
{
  "tenantId": "ke.bomet",
  "businessService": "PGR",
  "transitions": [
    { "fromState": "PENDINGFORASSIGNMENT", "action": "APPLY",  "toState": "PENDINGFORASSIGNMENT",
      "subscribers": ["CITIZEN"],            "channels": ["SMS", "WHATSAPP"] },
    { "fromState": "PENDINGFORASSIGNMENT", "action": "ASSIGN", "toState": "PENDINGATLME",
      "subscribers": ["CITIZEN", "ASSIGNEE"], "channels": ["SMS", "WHATSAPP"] },
    { "action": "REJECT",  "toState": "REJECTED", "subscribers": ["CITIZEN"], "channels": ["SMS"] },
    { "action": "RESOLVE", "toState": "RESOLVED", "subscribers": ["CITIZEN"], "channels": ["SMS"] },
    { "action": "RATE",    "toState": "CLOSEDAFTERRESOLUTION", "subscribers": ["ASSIGNEE"], "channels": ["SMS"] }
    // ... one row per real transition (see §11 behavior table)
  ]
}
```

- `subscribers` validated against the **closed enum** (D3). NOT against `BusinessService.roles[]` (this is the blocker that broke PR #915's own seed).
- `fromState` optional (match on action+toState when omitted).
- **MDMS schema** (`PGR.NotificationRouting`) ships with the master so the configurator renders it.

**Configurator-friendly flat model (chosen).** The configurator renders MDMS masters as schema-driven editable datagrids but **skips array/object fields in the grid** (shown as read-only JSON). A nested `transitions[]` document would render as a JSON blob, not an editable grid. Therefore the **stored model is one MDMS record per transition**:

```jsonc
{ "tenantId":"ke.bomet", "businessService":"PGR",
  "fromState":"PENDINGFORASSIGNMENT", "action":"ASSIGN", "toState":"PENDINGATLME",
  "subscribers":["CITIZEN","ASSIGNEE"], "channels":["SMS","WHATSAPP"] }
```

PGR reads all rows for the `businessService` and assembles the routing table in memory. `subscribers`/`channels` are short enum arrays rendered as chip multi-selects (small datagrid enhancement, not a custom page). `x-unique = [tenantId, businessService, fromState, action, toState]`.

### 4.2 `PGR.NotificationTemplate` (config 2 — the "what")

**Key = `(audience, action, toState, channel, locale)`** — uses `action+toState` to match the routing master exactly and to disambiguate same-action transitions (RATE→CLOSEDAFTERRESOLUTION vs CLOSEDAFTERREJECTION). `eventName` (`COMPLAINTS.WORKFLOW.<action>`) is derived only for the emitted Kafka event, not the template key.

```jsonc
{
  "tenantId": "ke.bomet",
  "audience": "CITIZEN",             // CITIZEN | EMPLOYEE (normalized from subscriber group)
  "action": "ASSIGN",
  "toState": "PENDINGATLME",
  "channel": "SMS",                  // SMS | WHATSAPP | EMAIL
  "locale": "en_IN",                 // also sw_KE etc.
  "subject": null,                   // EMAIL only
  "body": "Dear Citizen, your complaint for {complaint_type} with ID {id} is assigned to {emp_name}...",
  "placeholders": ["complaint_type","id","emp_name"]
}
```

- Placeholder vocabulary = the existing `{id}`, `{complaint_type}`, `{emp_name}`, `{ulb}`, `{status}`, `{download_link}`, `{additional_comments}`, `{rating}`, etc.
- `audience` is normalized: any employee-type subscriber (ASSIGNEE/CREATOR/PREVIOUS_ASSIGNEE) → `EMPLOYEE` template; `CITIZEN` → `CITIZEN`. (Keeps the existing two-audience body model; we can split per-group later if needed.)
- **Open (R5):** legacy appends a second `PGR_DEFAULT_CITIZEN` message per citizen. The Phase-1 renderer must reproduce that (or the golden-output test fails) — modeled as a `DEFAULT` template, deferred to Phase 1.
- **Migration:** existing localization `PGR_<ROLE>_<ACTION>_<STATUS>_SMS_MESSAGE` bodies are seeded into this master via a one-time curated mapping (NOT a naive `split('_')` — multi-underscore statuses like `CLOSED_AFTER_RESOLUTION` break that).

### 4.3 Tenant scoping (Bomet)

PGR queries MDMS with the service's `tenantId = ke.bomet`. MDMS v2 inheritance returns root-inherited data, so we **seed at the level PGR queries**. For Bomet we seed `ke.bomet` (single active tenant); shared defaults can live at `ke`. Confirm during Phase 0 which level resolves.

## 5. Subscriber model (Novu) — upsert, don't fetch (D6)

Novu identifies subscribers idempotently by `subscriberId`. We **never GET-then-create**; we upsert.

- `subscriberId = tenantId + ":" + userUuid` (fallback `tenantId:mobile` for uuid-less citizens) — matches `DispatchPipelineService:64`.
- novu-bridge calls **`POST /v1/subscribers` (identify)** with the full profile PGR resolved: `phone` (with country code), `email`, `firstName`/`lastName`, `locale`, `data:{tenantId, role, serviceRequestId}` — then triggers.
- **Today there is no identify call** — `NovuClient.trigger()` only sets `to.subscriberId` + `to.phone` inline, so Novu never holds a real subscriber profile. This is the tracking gap; identify fixes it.
- Profile upsert is safe — Novu stores **preferences separately**, so re-identifying won't reset a citizen's channel preferences.
- **Efficiency:** identify guarded by a short-lived in-memory `subscriberId → identified` TTL cache to skip redundant calls (profiles rarely change).

## 6. Code changes

### 6.1 PGR (`backend/pgr-services`)
- `MDMSUtils`: `getNotificationRouting(tenantId, ri)` + `getNotificationTemplates(tenantId, ri)` — cached (existing TTL-cache pattern).
- **`NotificationRouter`** (new): `(businessService, fromState, action, toState) → List<SubscriberGroup>` + channels.
- **`SubscriberResolver`** (new): `SubscriberGroup → User + contact`. Absorbs the lines 86–116 employee-phone logic and the PI-history lookups (`getEmployeeName(..., ASSIGN)` for assignee/previous-assignee).
- **`TemplateRenderer`** (new): pick template from config 2 by `(audience, eventName, channel, locale)`, fill placeholders, localize — replaces `getCustomizedMsg` + the hardcoded `getFinalMessage` blocks. **Localization happens here (D4).**
- `NotificationService`: refactor to `for each (recipient × channel) → render → publish event`. Remove the `NOTIFICATION_ENABLE_FOR_STATUS` gate + 7 if-blocks. Behind flag `pgr.notification.config.driven` (legacy retained).
- Per-event `transactionId = serviceRequestId:action:toState:subscriberId:channel` (idempotency, §10).

### 6.2 novu-bridge (`backend/novu-bridge`)
- `DispatchPipelineService`: **stop** calling `ConfigServiceClient.resolveTemplate` + locale fallback; read pre-rendered `renderedBody`/`subject` from the event.
- Add **subscriber identify** (`POST /v1/subscribers`) before trigger (§5).
- `NovuProviderStrategyFactory`: add **`BaileysProviderStrategy`** for `channel=WHATSAPP` (set `providerName=baileys` so it doesn't collide with `WhatsAppBusinessApiProviderStrategy`, which claims bare `whatsapp`). **Decision (R10): WhatsApp is wrapped as a Novu provider** — Baileys is registered behind a Novu generic/webhook integration so Novu delivers AND tracks WhatsApp alongside SMS/email (single tracking pane). novu-bridge triggers Novu as for any channel; Novu's webhook provider posts to the Baileys send-service.
- Keep `nb_dispatch_log` + retry/DLQ; extend unique key to include channel + recipient + transactionId.

### 6.3 Baileys WhatsApp send-service
- Small HTTP service wrapping Baileys (`makeWASocket` + `useMultiFileAuthState`), exposing `POST /send {to, text}` and a QR-pairing/auth-state volume. Novu (or novu-bridge's Baileys strategy) calls it.
- ⚠️ Unofficial WhatsApp API — ToS risk / number-ban risk. Acceptable "for now"; revisit official WhatsApp Business API later.

## 7. What you (operator) must provide per channel

| Channel | What's needed from you | Where it's configured |
|---|---|---|
| **SMS** | SMS provider choice (e.g. existing eGov gateway, or a Novu SMS integration) + **API key/secret**, **sender ID / short code** (approved for Kenya), country/number format. | Novu dashboard integration (or `ProviderDetail` seed). Confirm Bomet's current SMS gateway creds. |
| **Email** | An email provider: **SMTP host/port/user/pass** OR **SendGrid/SES API key**, and a **verified "from" address/domain** (SPF/DKIM for deliverability). | Novu email integration. |
| **WhatsApp (Baileys)** | A dedicated **WhatsApp number/account** to pair (scan QR once), agreement to persist Baileys **auth state** (volume), acceptance of unofficial-API risk. | Baileys send-service + Novu custom/generic provider. |

**Minimum to start:** SMS creds + sender ID (Bomet's immediate need). Email + WhatsApp can follow once their providers are ready.

## 8. Infra — Novu on Bomet (D5)

**Deploy path:** Bomet deploys via `local-setup/ansible/deploy.sh bomet` (host_vars `inventory/host_vars/bomet.yml`, overlay `local-setup/docker-compose.bomet.yml`, `playbook-deploy.yml`). The old `tilt-demo/ansible/deploy-bomet.sh` is removed — ignore the stale root CLAUDE.md.

**The Novu stack already exists** in `local-setup/docker-compose.egov-digit.yaml` behind the **`notifications` compose profile** (`novu-mongo`, `novu-api`, `novu-worker`, `novu-ws`, `novu-dashboard`, `novu-bridge`, `digit-user-preferences-service`). So this is **enable + configure**, not port:

- Set `COMPOSE_PROFILES=notifications` (+ existing) for Bomet via host_vars/digit.env, supply `NOVU_API_KEY` (two-pass: deploy → sign up at `/novu/` → paste key → redeploy).
- Add the **one new service**: `baileys-send-service` (new compose block + image).
- ⚠️ **Ansible reverts server-side compose edits** (`playbook-deploy.yml` re-copies compose each run) → all changes in tracked files (`docker-compose.egov-digit.yaml`, `docker-compose.bomet.yml`, `host_vars/bomet.yml`, `digit.env.j2`); never `vi` on the box; named volumes declared in tracked compose.
- Build custom images (`pgr-services`, `novu-bridge`, `baileys`) on the CI server → push to registry `10.0.0.4:5000` → Bomet pulls.
- Topics: `complaints.domain.events`, `novu-bridge.retry`, `novu-bridge.dlq`.

## 9. Configurator

Register both MDMS schemas (`PGR.NotificationRouting`, `PGR.NotificationTemplate`) with UI hints; the configurator's schema-driven MDMS views render/edit them. Operators edit routing + templates without code.

## 10. Rollout, idempotency, fallback

- **Feature flag** `pgr.notification.config.driven` (default off → on per tenant). Legacy path kept one release.
- **Seed = exact current behavior** (§11) so cutover is behaviorally a no-op; changes are config-only afterward.
- **Idempotency:** we now emit multiple events per transition (one per recipient×channel). Stable `transactionId` (§6.1) + `nb_dispatch_log` unique key prevent double-send on Kafka redelivery.
- **Failure isolation:** a per-recipient render/publish failure must not drop the others (the current code's single try/catch swallows the rest).

## 11. Behavior table — seed source of truth

Derived from current code (subject to Bomet's actual `NOTIFICATION_ENABLE_FOR_STATUS`); each row verified against `PgrWorkflowConfig.json` before seeding:

Validated against `PgrWorkflowConfig.json` (key = `action + toState`; `ASSIGNEE` resolves to the current workflow assignee, falling back to the last ASSIGN from process-instance history for REOPEN/RATE):

| action → toState | CITIZEN | ASSIGNEE | note |
|---|---|---|---|
| APPLY → PENDINGFORASSIGNMENT | ✅ | — | confirmation |
| ASSIGN → PENDINGATLME | ✅ | ✅ | from PENDINGFORASSIGNMENT **or** PENDINGFORREASSIGNMENT |
| REASSIGN → **PENDINGFORREASSIGNMENT** | ✅ | ✅ | from PENDINGATLME |
| REJECT → REJECTED | ✅ | — | |
| RESOLVE → RESOLVED | ✅ | — | |
| REOPEN → PENDINGFORASSIGNMENT | ✅ | ✅ | assignee via ASSIGN history |
| RATE → CLOSEDAFTERRESOLUTION | — | ✅ | assignee via ASSIGN history |
| RATE → CLOSEDAFTERREJECTION | — | ✅ | assignee via ASSIGN history |

⚠️ Corrections vs the original spec & my first draft: REASSIGN lands on **PENDINGFORREASSIGNMENT** (not PENDINGATLME); the legacy `PENDINGATLME && REASSIGN` block is **dead code** (REASSIGN never lands on PENDINGATLME) and is excluded. `action + toState` disambiguates APPLY vs REOPEN (shared toState) and the two RATE targets. → 8 routing rows, 11 templates (audience×action×toState). Seeded + cross-checked (0 missing, 0 orphan).

## 12. End-to-end testing strategy

### 12.1 Unit (PGR)
- `NotificationRouter`: table of `(transition) → expected subscriberGroups` for every row in §11.
- `SubscriberResolver`: each group → correct user/contact, incl. PI-history (assignee, previous-assignee), and null-safety (no citizen uuid, no assignee).
- `TemplateRenderer`: placeholder substitution + locale selection + missing-template fallback.

### 12.2 Backward-compatibility (the critical gate)
- **Golden-output test:** for each §11 transition, assert the set of `(recipient, channel, renderedBody)` events from the **config-driven path** equals what the **legacy path** produced (same fixtures, flag on vs off). The cutover must be a behavioral no-op. This is what would have caught PR #915's dropped-recipient narrowing.

### 12.3 Integration (CI server, per `ci-testing` skill)
- Seed routing + template masters; drive real PGR transitions (file → assign → resolve → rate) via API; assert events on `complaints.domain.events` (subscriberId, channel, renderedBody, transactionId).
- **Idempotency:** replay a Kafka event; assert `nb_dispatch_log` dedups (one delivery).
- **Tenant scoping:** confirm `ke.bomet` resolves the seeded master.

### 12.4 novu-bridge
- Subscriber **identify upsert**: first event creates subscriber, second updates (mock Novu, assert `POST /v1/subscribers` payload incl. profile + `data`).
- Provider routing: SMS→gateway strategy, EMAIL→email, WHATSAPP→Baileys.
- Pass-through: bridge does NOT call config-service template resolve.

### 12.5 Channel delivery (staging/Bomet)
- **SMS:** real send to a test MSISDN; confirm receipt + Novu activity feed shows the subscriber + delivery status.
- **Email:** real send; confirm inbox + Novu tracking.
- **WhatsApp (Baileys):** paired test number; `baileys-test.js`-style send; confirm receipt + bridge dispatch_log.
- **Tracking:** verify each subscriber appears in the Novu dashboard with profile + per-message delivery status (the gap we're closing).

### 12.6 E2E (UI, Playwright — optional)
- Citizen files → employee assigns → resolves on Bomet UI; assert the citizen/assignee receive the expected SMS/WhatsApp at each step (smoke-level, against the seeded config).

## 13. Phasing

- **Phase 0** — branch off develop (done); author 2 MDMS masters + schemas; seed Bomet to reproduce §11; register in configurator.
- **Phase 1** — PGR refactor (router + resolver + renderer) behind flag; emits pre-rendered events; golden-output + unit tests; verify on CI.
- **Phase 2** — stand up Novu + baileys on Bomet (compose port); novu-bridge pass-through + identify + Baileys strategy.
- **Phase 3** — cut Bomet over (flag on): **SMS first**, then WhatsApp, then email; verify tracking.

## 14. Open items / risks
- Baileys = unofficial WhatsApp (ban risk). Confirm dedicated number + acceptance.
- SMS/email provider credentials are operator-supplied (§7) — Phase 3 gated on them.
- Confirm Bomet's actual `NOTIFICATION_ENABLE_FOR_STATUS` to finalize the active seed set.
- `digit-user-preferences-service` on Bomet: needed if we honor per-citizen channel preferences; else `NOVU_BRIDGE_PREFERENCE_ENABLED=false` initially.
