# Keycloak parity harness

Proves that enabling the Keycloak adapter does **not** change what DIGIT sees.

Two deployments on Bomet run the **identical** `digit-ui` build; only
`globalConfigs` differs:

| | URL | auth |
|---|---|---|
| baseline | https://bometfeedbackhub.digit.org | `citizen/employeeAuthProvider = digit` |
| keycloak | https://kc.egov-digit.theflywheel.in | `citizen/employeeAuthProvider = keycloak` |

Keycloak is a pass-through: `token-exchange-svc` swaps the KC JWT for a native
DIGIT token, so the calls DIGIT receives must be identical. Any divergence is a
token-exchange-svc / realm bug — never a UI change.

## Run

```bash
./run-parity.sh boot
./run-parity.sh citizen  --mobile=712345678 --otp=123456
./run-parity.sh employee --user=ADMIN --pass=eGov@123 --city="Bomet County"
```

Outputs `out/<journey>-{baseline,kc}.json`, a screenshot per run, and
`out/<journey>-report.json`. Exit code 1 when a divergence survives.

## What it compares

Per request: method, normalized path, status, **credential kind** (uuid = native
DIGIT vs jwt = Keycloak), request-body key shape (incl. `RequestInfo`), plus the
resulting localStorage session. Secrets/PII are never recorded — only key names
and token *shapes*.

Classification:
- **auth-plane** (`/auth/realms/*`, `/kc/realms/*`, `/user/oauth/token`) — the login
  mechanism itself, expected to differ.
- **by-design overlay translation** — `/kc/<api>` is the same endpoint as `<api>`;
  the browser sends a JWT, the overlay forwards a DIGIT uuid token upstream
  (verify in the overlay's `[UPSTREAM]` logs).
- **divergence** — MISSING / EXTRA / STATUS / AUTH_SHAPE / BODY_SHAPE.

## Findings this harness produced

1. `/kc` routed via Kong 401s (`InvalidAccessTokenException`): Kong's global DIGIT
   auth plugin rejects the KC JWT *before* the overlay can exchange it. `/kc/` must
   proxy straight to token-exchange-svc.
2. Realm `ke` had `quickLoginCheckMilliSeconds=1000`; the overlay's legitimate
   fail→provision→retry pattern tripped it. Set to 0 (brute-force + failureFactor
   kept).
3. The deployed `token-exchange-svc` image predated `deriveKcPassword`, so citizen
   OTP logins 401'd after a valid DIGIT auth. Rebuilt from source.

## Two instances (pick per your constraint)

| | URL | UI code | Keycloak's role |
|---|---|---|---|
| **adapter** | `kc.egov-digit.theflywheel.in` | patched bundle (`KeycloakAuthAdapter.employee-fix.patch`) | KC issues the session; browser holds a JWT; overlay exchanges it per call |
| **pure** | `kcpure.egov-digit.theflywheel.in` | **STOCK, unmodified** (same build + globalConfigs as live) | only `/user/oauth/token` is diverted to the overlay; browser holds a native DIGIT uuid token |

The **pure** instance exists because the stock bundle provably cannot support the
adapter path without code changes (`_digitUserType` is only ever assigned `null`,
and `login()` reads only the three OIDC tokens). It reaches parity *by
construction* — same tokens, same endpoints, no `/kc` prefix, nothing to
translate — with **zero UI changes**.

Honest limitation of the pure variant: DIGIT stays the credential authority (it
owns citizen OTPs and employee passwords) and Keycloak is kept as an identity
mirror. Making KC authoritative there needs the provisioning path wiring the
ROPC flow already has.

## Deployment layout on Bomet

| | serves | bundle |
|---|---|---|
| live | `bometfeedbackhub.digit.org/digit-ui/` | `/opt/ccrs/digit-ui-esbuild/build` (untouched) |
| parity | `kc.egov-digit.theflywheel.in/digit-ui/` | `/opt/ccrs/digit-ui-esbuild-kc/build` (patched) |

The parallel vhost additionally routes `/kc/` and `/mdms-v2/` straight to
token-exchange-svc (port 18300). Going via Kong is fatal: a global DIGIT auth
plugin rejects the Keycloak JWT before the overlay can exchange it.

## The one UI change (`KeycloakAuthAdapter.employee-fix.patch`)

Applied only to the parity bundle. The stock adapter never reads the overlay's
`digit_user_type` / `digit_roles`, and `login()` ends in
`window.location.replace()` which discards in-memory state — so **every** SSO
user was classified `CITIZEN` and employee sessions could never build. The patch
captures those two fields at login and rehydrates them after the redirect.

## Ticket lifecycle (`lifecycle-through-kc.sh`)

Drives a real ticket end-to-end through the Keycloak instance:

```bash
SERVICE=GarbageMissedGarbageCollection ASSIGNEE=<hrms-employee-uuid> ./lifecycle-through-kc.sh
#  APPLY -> ASSIGN(PENDINGATLME) -> RESOLVE(RESOLVED) -> RATE(CLOSEDAFTERRESOLUTION)
```

Two PGR rules bite here and are **not** Keycloak-related (baseline enforces them
identically — verified with control runs):
- the assignee must be an HRMS employee **whose department matches the complaint
  type's department**. On Bomet nothing carries `ROADS_PUBLIC_WORKS`, so
  `DamagedRoad` is unassignable there; `GarbageMissedGarbageCollection` (ENV) works.
- `ADMIN` has no HRMS department at all, so it can never be an assignee.

### The write-path fix this uncovered

The overlay forwarded the shared **system token** on every upstream call while
setting `RequestInfo.userInfo` to the acting user. Reads tolerate that, but DIGIT
write paths validate the role **on the token**, so PGR `APPLY` rejected a
system/ADMIN token carrying citizen userInfo with `INVALID_ROLE`.
`preCacheDigitUser` now stores the acting user's own DIGIT token (minted from the
supplied credentials, or reused from the DIGIT fallback auth) and
`user-resolver` stops discarding it on a cache hit.

## Results

| journey | verdict |
|---|---|
| boot | ✅ full parity |
| citizen | ✅ full parity |
| employee | ✅ full parity — lands on `/digit-ui/employee`, same DIGIT endpoints |

DIGIT receives byte-identical traffic on all three; the only difference is the
credential the *browser* holds (KC JWT), which token-exchange-svc exchanges for a
native uuid token before anything reaches DIGIT.

### Two divergences worth remembering

**Session must be built from the real DIGIT user.** The overlay now returns
`digit_user` beside the tokens, and the adapter projects it to exactly the fields
native auth persists. Building the session from JWT claims alone dropped
`id`/`userName`/`mobileNumber`/`locale`/`permanentCity`/`active` and made DIGIT
components take different code paths.

**Never preset `CITIZEN.COMMON.HOME.CITY`.** `TopBar` gates its
`events/notifications/_count` request on `getCitizenCurrentTenant(true)` — i.e. on
that key existing. Native citizen login leaves the home city unselected, so
seeding it made the SSO session issue a call the default UI never sends.
