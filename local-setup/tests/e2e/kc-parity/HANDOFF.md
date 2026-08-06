# HANDOFF — UI-only PGR lifecycle on Keycloak

> Written to survive context compaction. Everything needed to resume is here.

## Goal
A Playwright E2E that drives **raise → assign → resolve entirely through the UI**.
Get it green on the **baseline** (no Keycloak), then make the **same spec** pass
against the Keycloak instance, changing only the adapter (`KeycloakAuthAdapter.js`),
the Keycloak realm, or `token-exchange-svc`. Creating staff/master data on Bomet
is explicitly authorised.

## Deployments (all on Bomet; live `bometfeedbackhub` config untouched)
| name | URL | notes |
|---|---|---|
| baseline | `https://bometfeedbackhub.digit.org` | live, `authProvider=digit` |
| **kc** | `https://kc.egov-digit.theflywheel.in` | canonical KC instance, patched bundle `/opt/ccrs/digit-ui-esbuild-kc/build` |
| kcadapter | `https://kcadapter.egov-digit.theflywheel.in` | adapter on, stock bundle |
| kcpure | `https://kcpure.egov-digit.theflywheel.in` | stock bundle, KC wired server-side |

Overlay: image `token-exchange-svc:pure`, source `/opt/token-exchange-src` on the box.
Rebuild + redeploy:
```bash
cd /opt/token-exchange-src && docker build -t token-exchange-svc:<tag> .
# edit image: in /opt/digit/docker-compose.egov-digit.yaml, then
COMPOSE_PROFILES=search,mcp,notifications,keycloak docker compose \
  -f docker-compose.egov-digit.yaml -f docker-compose.bomet.yml \
  up -d --no-deps --force-recreate token-exchange-svc
```
Overlay logs: `curl localhost:18300/logs?last=40&format=text` — `[UPSTREAM]` token
must be a **uuid**, never a JWT.

## Files (all committed)
- `pgr-ui-lifecycle.spec.ts` — the 3-step UI spec (`BASE_URL` switches deployment)
- `probe-cascade.js` — citizen wizard probe (`B=<url>`)
- `probe-employee.js` — employee login/inbox/detail/search probe (`B`, `EMP_USER`, `EMP_PASS`, `CID`)
- `probe-city-index.js` — which "Bomet County" entry authenticates
- `run-parity.sh` / `diff.js` / `capture.js` — network parity harness
- `lifecycle-through-kc.sh` — API-level lifecycle (already passes on KC)

Run (from `local-setup/tests`), **always backgrounded** — runs take 3-6 min and
foreground calls get killed:
```bash
BASE_URL=https://bometfeedbackhub.digit.org EMP_USER=ADMIN EMP_PASS=<ask owner> \
  npx playwright test e2e/kc-parity/pgr-ui-lifecycle.spec.ts --project=chromium --workers=1 \
  > /tmp/run.log 2>&1
```
Probes write to a log file and are polled — never pipe through `tail`, it buffers.

## STATUS
**Step 1 (citizen files a complaint) — GREEN on baseline**, 3 clean runs.
`advanceWizard()` + `fillCascade()` drive the whole form generically.

**Steps 2–3 (assign → resolve) — GREEN on baseline.** Proven on
`PG-PGR-2026-08-06-167858`: assign → *Pending at last mile employee*
(assignee "Super Admin (Health & Sanitation)") → resolve → **Resolved**.

Pass `CID=<existing complaint>` to skip the 3-minute citizen wizard while
iterating on steps 2/3 — step 1 self-skips.

## Environment traps worth keeping
- **The oauth client is NOT the stock `egov-user-client:egov-user-secret`.** The
  browser logs in fine (200) while that documented curl recipe 401s on *client*
  auth — the header this deployment sends decodes to only 18 bytes. Any API-level
  probing must reuse the header the browser actually sends. `global-setup` already
  warns `Kong auth returned HTTP 401` for this reason; it is a stale credential in
  the harness, not a broken stack.
- **Bomet's live UI is a hand-overlaid bundle.** `/var/web/digit-ui/` was manually
  overwritten inside the `digit-ui` container (Aug 5 15:31) on top of the
  `nightly-develop` image. It is not reproducible from any commit, is *not* built
  from `/opt/digit-ui-esbuild`, and **the next redeploy silently destroys it**.
  HMR is off (no `esbuild` tmux session) despite what the root CLAUDE.md says.
  It also predates several merged fixes — `isComplaintLookup` (PR 1561),
  `CMS_RECEPTION_OFFICER` (PR 1533), `filedOnBehalfOfCitizen` (PR 1566) are all
  absent from the live bundle.

## Test accounts

**Secrets are deliberately not recorded here — ask the deployment owner.** The
citizen OTP is mocked at the gateway (Kong `request-termination` answers 200 for
`/user-otp` and `/otp`), so no SMS is ever sent.

| who | identifier | secret | notes |
|---|---|---|---|
| citizen | test mobile (Demo Test Citizen) | fixed mock OTP | `permanentCity=ke.bomet` set (benign, from a disproved experiment) |
| employee | `ADMIN`, tenant `ke` | ask owner | 22 roles incl GRO+PGR_LME; also has HRMS record `ADMIN_HRMS` |
| staff | `E2EKE1507`, tenant `ke` | ask owner | created; DEPT_3; GRO+PGR_LME+EMPLOYEE |

## Master data created/changed on Bomet (authorised)
1. **`ADMIN_HRMS`** — HRMS record attached to the *existing* ADMIN user: tenant `ke`,
   DEPT_3, jurisdictions `ADMIN/PWB1_105269771/Country` + `REVENUE/bomet/City`.
2. **`E2EKE1507`** — new employee, same dept/jurisdictions, known password.
3. **Complaint types** — `Sample*` sub-types repointed from `DEPT_1` (no staff) to
   `DEPT_3` in `eg_mdms_data` (schemacode `RAINMAKER-PGR.%`).
4. **ACCESSCONTROL** — 660 rows copied `pg` → `ke`; `egov-accesscontrol` restarted.
   (Did NOT change UI behaviour; `ke` already had 922 rows. Harmless, left in place.)

### HRMS gotchas (cost hours — keep)
- `_create` fails `ERR_HRMS_GENERATE_ID_ERROR` unless you pass an explicit `code`.
- `_create` validates roles against its own master: ADMIN's full 22-role set is
  rejected with `ERR_HRMS_INVALID_ROLE` — send only `GRO`/`PGR_LME`/`EMPLOYEE`.
- `_update` rejects jurisdiction **removals** (`ERR_HRMS_UPDATE_JURISDICTION_INCOSISTENT`)
  — always append to the existing list.

## Working selectors (hard-won)
- citizen login: `/digit-ui/citizen/login`, `input[type=tel]`, 6 OTP boxes
- employee login: `#emp-username`, `#emp-password`; city is an `<li>`; the privacy
  checkbox toggles **only** via `label[for="privacy-component-check"]`; then
  `button[type=submit]`; a first-login **language-selection** screen follows
  (click "English" → "Continue")
- complaint wizard = **N-level cascade** `AUTHORITY_TYPE → MAIN_CATEGORY → SECTOR →
  SUB_TYPE`, `<button>`s reading `Select…`; later steps read **`Select County`**, so
  the matcher must be `/^Select(…|\s|$)/` — this was the single fix that unblocked step 1
- wizard steps: cascade → **map pin** → **Location Details** (`#postal-code`,
  `#landmark`, `County*`) → description → SUBMIT

## THE BLOCKER — SOLVED (root cause: I was driving routes that do not exist)

For a long time every employee appeared to see a broken product: no Inbox card,
`/pgr/inbox` with 0 rows, `/pgr/complaint/details/{id}` with **no buttons at all**.
None of that was real. **Two of the three URLs were never registered routes.**

| what I used | what actually exists |
|---|---|
| `/employee/pgr/inbox` | **`/employee/pgr/inbox-v2`** |
| `/employee/pgr/complaint/details/:id` | **`/employee/pgr/complaint-details/:id`** (one segment, hyphen) |

An unmatched `PrivateRoute` inside `AppContainer` still renders the chrome and a
breadcrumb — so a route miss looks *exactly* like a broken screen: body text
"Home", zero `<button>`s, 0 rows. That signature cost days. **If a DIGIT screen
renders only chrome, suspect the route before the data.**

Two more things that are by design, not bugs:
- **There is no Inbox card, and there should not be.** `PGRCard.js` renders exactly
  two links: `create-complaint` (CSR only) and `ACTION_TEST_SEARCH_COMPLAINT →
  inbox-v2`. "Search Complaint" **is** the inbox. The MDMS rows pointing at the old
  `/pgr/inbox` are all `enabled: false` and over a year stale.
- **inbox-v2 opens on the "My Complaints" tab**, which is empty until something is
  assigned to you. An unassigned complaint only shows under **"All Complaints"**.

Hypotheses eliminated by test (do NOT redo — all were sound, all were irrelevant):
- ❌ missing PGR roles — ADMIN already had GRO + PGR_LME
- ❌ missing HRMS record — created `ADMIN_HRMS`, no change
- ❌ ACCESSCONTROL rows missing at `ke` — merged from `pg`, no change
- ❌ jurisdiction — appended `REVENUE/bomet/City`, no change
- ❌ postal-code format / React controlled-input — values set fine, no validation error

The master data created along the way (see above) is harmless and was left in place,
but **none of it was required** to unblock this.

### Verified working employee flow (all selectors confirmed live)
```
inbox-v2 → "All Complaints" tab → a[href*="complaint-details"]
complaint-details/:id → button "Take action"
  → .header-dropdown-option  (Reject | Assign | Escalate; Resolve once assigned)
  → .popup-module
      .assignee-dropdown-container input   (placeholder "Select Employee")
      → .digit-dropdown-item
      textarea (comments)
      → button "SUBMIT"
```
Status is rendered as a **localised label**, not a code — assert on
"Pending for assignment" / "Pending at last mile employee" / "Resolved",
never `PENDINGATLME`.

## GOAL MET — the same spec is green on both deployments

```
baseline  https://bometfeedbackhub.digit.org   3 passed   PG-PGR-2026-08-06-168211
keycloak  https://kc.egov-digit.theflywheel.in 3 passed   PG-PGR-2026-08-06-168243
```
Raise → assign → resolve, UI only, **byte-identical spec**, no per-deployment
branches. Two fixes were needed, both inside the allowed surface.

### Fix 1 — adapter: citizen session tenant (`KeycloakAuthAdapter.citizen-tenant-fix.patch`)
```js
const tenantId = this._tenantId || defaultCityTenant;          // before
const tenantId = this._tenantId
  || (du && (du.permanentCity || du.tenantId))                 // after
  || defaultCityTenant;
```
A citizen signing in through Keycloak never sees the employee tenant dropdown, so
`this._tenantId` is null and the session fell through to a synthesized
`<state>.citya` — `pg.citya` here, a tenant that does not exist on Bomet. The
overlay had *already* resolved the real user at tenant `ke`; the adapter just
wasn't using it. (`permanentCity` is not a column in this schema, so it is always
undefined and `du.tenantId` is what actually applies — the term is kept because
deployments that do carry it should prefer the home city.)

### Fix 2 — overlay: a stale compatibility rewrite (`token-exchange-svc.boundary-rewrite-fix.patch`)
With fix 1 in place the create still 400'd:
```
INVALID_BOUNDARY_CODE — Invalid locality code: PMC_Z2_B1_L1
```
The overlay was rewriting every boundary call:
```
[BOUNDARY-FIX] ...?tenantId=ke&hierarchyType=ADMIN → ...?tenantId=pg.citya&...&boundaryType=City
```
Both halves are demo-specific and wrong here: `DIGIT_DEFAULT_TENANT` was never set
for Bomet so it still held the `pg.citya` default, and Bomet's hierarchy is rooted
at **County**, so forcing `boundaryType=City` filtered away every real node. The
wizard offered *South Delhi → Hauz Khas* and PGR rightly rejected the result. The
block is now skipped unless the configured city tenant is genuinely a child of the
state tenant, so the pg demo keeps its fix and everyone else is left alone.

**How it was found:** capture the same request on both deployments and diff. Same
URL, same body, same `userTenant=ke` — but baseline returned `["BOMET(County)"]`
and KC returned `["PMC_CITY_ROOT(City)", …]`. A response that differs for an
identical request means something between you and the service is rewriting it.

### Parity after these fixes
`boot`, `employee`, `inbox` → FULL PARITY, unchanged. `citizen` reported one
`MISSING POST /user/citizen/_create` on a single run and FULL PARITY on the two
re-runs after it — baseline's citizen auto-registration fires occasionally for a
fresh session, and KC has no equivalent because the overlay provisions the user.
**Re-run `citizen` before believing a divergence there**; it is the one flaky
journey.

## Already proven on KC (don't redo)
- `run-parity.sh boot|citizen|employee|inbox` → **FULL PARITY** on `kc`
- `lifecycle-through-kc.sh` → `PG-PGR-2026-08-06-167823` reached
  **CLOSEDAFTERRESOLUTION**, every call 200 (API level)
- 8 fixes landed (6 server-side + 2 adapter lines) — see `README.md`
