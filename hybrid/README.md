# hybrid-shim

A stateless 2.x → v3 translation shim. CCRS (DIGIT 2.12) keeps talking its own
API; the shim rewrites each call into DIGIT 3.0 REST, calls the v3 Kong, and
rebuilds the 2.x response envelope. CCRS changes only `EGOV_*_HOST`.

Lives at `/opt/hybrid-shim` on **egov-subhashini (37.27.254.159)**, compose
project `hybrid`, port **9100**, attached to both `digit_egov-network` (2.x)
and `digit3_default` (v3). Rebuild after a code change:

```bash
cd /opt/hybrid-shim && docker compose -p hybrid up -d --build
```

| path | role |
|---|---|
| `src/server.js` | :9100, routes on the inbound 2.x path, registers translators |
| `src/router.js` | longest-prefix router; register prefixes **without** a trailing slash |
| `src/context.js` | `RequestInfo` → `X-Tenant-ID` / `X-User-ID` |
| `src/client.js` | JSON client for `http://d3-kong:8000` |
| `src/gaplog.js` | append-only `gaps.jsonl`; a silent default is a bug |
| `src/translators/*.js` | one `{toV3, toV2}` per re-pointed dependency |
| `baseline/` | the 2.x lifecycle harness — see `baseline/README.md` |

---

## Re-pointed dependencies

| 2.x dependency | prefix | v3 service | status |
|---|---|---|---|
| `egov-idgen` | `/egov-idgen` | `idgen` (`/idgen/v3/*`) | **live** — pgr-services only |

### idgen (Task 3)

`pgr-services` posts `{EGOV_IDGEN_HOST}egov-idgen/id/_generate`
(`egov.idgen.path`) and reads `idResponses[].id`. The shim maps that to
`POST /idgen/v3/generate` (or `/idgen/v3/generate/bulk` when the 2.x batch asks
for more than one id). Field-by-field notes, all probed live and not guessed,
are in the header comment of `src/translators/idgen.js`.

**The template must exist in v3 before generation works.** Its shape is taken
from the live 2.x source of truth — MDMS `common-masters.IdFormat` at tenant
`pg`, `idname = pgr.servicerequestid`, `format = PG-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]`
— *not* from the `PB-PGR-...` default in `pgr-services/application.properties`,
because the live 2.x idgen prefers MDMS over the format the caller sends
(verified: a call sending `PB-PGR-…` returned `PG-PGR-2026-09-20-005216`).

Registered once with **exactly** this command (also kept as
`register-idgen-template.sh`):

```bash
curl -s -m 15 -X POST \
  -H 'X-Tenant-ID: pg' \
  -H 'X-User-ID: hybrid-shim-task3' \
  -H 'Content-Type: application/json' \
  -d '{"templateCode":"pgr.servicerequestid","config":{"template":"PG-PGR-{DATE:yyyy-MM-dd}-{SEQ}","sequence":{"scope":"GLOBAL","start":6000,"padding":{"length":6,"char":"0"}}}}' \
  http://localhost:8000/idgen/v3/template
```

Token translation is by hand: `[cy:yyyy-MM-dd]` → `{DATE:yyyy-MM-dd}`,
`[SEQ_EG_PGR_ID]` → `{SEQ}`. `padding` **must** be an object — v3's binder
silently ignores `paddingLength` / `paddingChar` and leaves `padding: null`.
`start: 6000` is a hand-picked offset above the live 2.x counter; see the gap
log for why that is fragile.

Then record the registration-time gaps (once per fresh v3 template store):

```bash
node /opt/hybrid-shim/record-template-gaps.js
```

Useful neighbours, discovered while probing:

```bash
# list / fetch
curl -H 'X-Tenant-ID: pg' -H 'X-User-ID: me' http://localhost:8000/idgen/v3/template
curl -H 'X-Tenant-ID: pg' -H 'X-User-ID: me' 'http://localhost:8000/idgen/v3/template?templateCode=pgr.servicerequestid'
# delete - BOTH templateCode and version are required, as query params
curl -X DELETE -H 'X-Tenant-ID: pg' -H 'X-User-ID: me' \
  'http://localhost:8000/idgen/v3/template?templateCode=zz.probe&version=v1'
```

#### Switching CCRS over, and back

`EGOV_IDGEN_HOST` appears four times in
`/opt/digit/docker-compose.egov-digit.yaml` — `egov-user` (972),
`boundary-service` (1203), `egov-hrms` (1510) and `pgr-services` (1808).
**Only the pgr-services one is re-pointed.** Original value:
`http://egov-idgen:8088/` (the trailing slash matters — CCRS concatenates host
and path).

`flip_idgen.py` edits that one line and refuses if it cannot find exactly one
match inside the `pgr-services:` block:

```bash
python3 /opt/hybrid-shim/flip_idgen.py shim   # -> http://hybrid-shim:9100/
python3 /opt/hybrid-shim/flip_idgen.py orig   # -> http://egov-idgen:8088/  (rollback)

cd /opt/digit && docker compose -p digit \
  -f docker-compose.egov-digit.yaml -f docker-compose.fast-path.yml \
  -f docker-compose.migrations.yml -f docker-compose.monitoring.yml \
  -f docker-compose.matomo.yml up -d pgr-services
```

The full `-f` list is required: the `digit` project was created from all five
files, and restarting with fewer would drop the others' overrides. A pristine
copy of the file as it was before Task 3 is kept at
`/opt/hybrid-shim/docker-compose.egov-digit.yaml.task3-orig`.

#### Proof it is really v3 serving the ids

The v3 template's counter starts at 6000 while the 2.x sequence
`seq_eg_pgr_id` was at 5216. Complaints created after the switchover are
`PG-PGR-2026-09-20-006001` and `…-006002` — numbers the 2.x idgen could not
have produced.

```bash
OUT=/opt/hybrid-shim/runs/idgen-$(date +%H%M).json bash /opt/hybrid-shim/baseline/lifecycle.sh
bash /opt/hybrid-shim/baseline/compare.sh \
  /opt/hybrid-shim/baseline/baseline-20260920-0238.json /opt/hybrid-shim/runs/idgen-XXXX.json
```

Both runs: 7/7 PASS, `EQUIVALENT: 7 steps, 731 compared facts`, with
`compare.sh`'s ignore list **unchanged**.

---

## Tests

```bash
cd /opt/hybrid-shim && node --test test/      # 15/15, no npm dependencies
```

`test/idgen.test.js` redirects `GAP_LOG` to a temp dir, so running the suite
never writes to the real `gaps.jsonl`.

## Gap log

`/opt/hybrid-shim/gaps.jsonl` (a symlink into the container's bind mount at
`gaps/`). One JSON object per line. Every invented default, dropped field or
faked response key lands here — including ones recorded per request, so a
dependency that is quietly lossy shows up as volume, not silence.
