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
| `boundary-service` | `/boundary-service` | `boundary` (`/boundary/v3/*`) | **live** — pgr-services only |

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


### boundary (Task 4)

**`EGOV_BOUNDARY_HOST` serves TWO endpoints, not one.** Moving the variable
moves both, so both must be translated or the forgotten one gets the wrong
envelope:

| 2.x path | CCRS caller | v3 |
|---|---|---|
| `/boundary-service/boundary/_search` | `ServiceRequestValidator.validateBoundary` — is this complaint locality real? | `GET /boundary/v3/boundaries` |
| `/boundary-service/boundary-relationships/_search` | `BoundaryHierarchyExpander.fetch` — which boundary codes may this employee see? | `GET /boundary/v3/relationship` |

`src/translators/boundary.js` dispatches on the sub-path and **throws** on any
third `/boundary-service/*` path (502 `SHIM_ERROR`) rather than guessing. The
first switchover attempt did guess — one translator for the whole prefix — and
the relationship call came back as a boundary envelope, so the employee's
jurisdiction expanded to the empty set and the final search returned **zero
complaints with HTTP 200 and no error anywhere**. Field-by-field notes, all
probed live, are in the header comment of the translator.

Two quirks worth knowing:

- The 2.x boundary search posts a **bare `RequestInfo`** as its body (not
  `{"RequestInfo":{...}}`) and puts the criteria in the query string, so
  `context.toHeaders()` yields an empty tenant. The translator builds
  `X-Tenant-ID` / `X-User-ID` itself, from `?tenantId=` first and the
  `userInfo` second. The relationship search *does* wrap it; both are handled.
- `egov.boundary.host` ends in `/` and `egov.boundary.search.url` starts with
  `/`, so the concatenation has a double slash — but Spring's `RestTemplate`
  normalises it before the wire (confirmed in boundary-service's own
  `TracerFilter` log; 2.x actually **500s** on the un-normalised form). The
  shim therefore sees a single slash and the registered prefix matches.

#### Seeding v3 first — the store starts empty

Nothing migrates 2.x boundary data. `GET /boundary/v3/boundaries` returned
`{"boundary":[]}` for every `pg` code, so CCRS could not validate a locality
until the hierarchy and boundaries existed in v3. Seeded with **exactly** this
script, transcribed from the live CCRS `boundary` / `boundary_relationship`
tables (`ADMIN`: `PG_STATE` > `PG_CITYA` > `PG_CITYA_Z1` > `PG_CITYA_B1` >
`SUN01_LOCALITY`, the chain the baseline's `LOCALITY=SUN01_LOCALITY` needs):

```bash
bash /opt/hybrid-shim/seed-boundaries.sh          # V3=, TENANT= are overridable
node /opt/hybrid-shim/record-boundary-gaps.js     # idempotent; once per fresh store
```

Only that chain was seeded. `SUN02_LOCALITY`, `SUN03_LOCALITY` and the whole
`pg.citya` tenant exist in 2.x and **do not exist in v3** — see the gap log.

#### Switching CCRS over, and back

`EGOV_BOUNDARY_HOST` appears three times in
`/opt/digit/docker-compose.egov-digit.yaml` — `egov-hrms` (1514, no trailing
slash), `egov-bndry-mgmnt` (1570) and `pgr-services` (1814). **Only the
pgr-services one is re-pointed.** Original value:
`http://boundary-service:8081/` (the trailing slash matters).

`flip_boundary.py` edits that one line and refuses if it cannot find exactly
one match inside the `pgr-services:` block:

```bash
python3 /opt/hybrid-shim/flip_boundary.py shim   # -> http://hybrid-shim:9100/
python3 /opt/hybrid-shim/flip_boundary.py orig   # -> http://boundary-service:8081/  (rollback)
```

then restart pgr-services with the **full five-file** `-f` list shown under
idgen above. A pristine copy of the file as it was before Task 4 is kept at
`/opt/hybrid-shim/docker-compose.egov-digit.yaml.task4-orig`.

> **Always `docker restart digit-pgr-services-1` before measuring a boundary
> change.** `BoundaryHierarchyExpander` caches each jurisdiction expansion for
> **30 minutes** (Caffeine `expireAfterWrite`) and falls back silently to the
> unexpanded code on any failure. `docker compose up -d pgr-services` with no
> config change leaves the container running and does **not** evict it, so a
> run made after a fix can still be measuring the state before it. This cost
> two red lifecycle runs during Task 4.

#### Proof it is really v3 serving the boundaries

pgr-services' own log shows every boundary call going to the shim, and the
seeded v3 rows carry `createdBy: hybrid-shim-task4` where the 2.x rows carry
`createdBy: system`:

```bash
docker logs digit-pgr-services-1 --since 15m 2>&1 \
  | grep -oE "hybrid-shim:9100/boundary-service/[a-z-]+/_search" | sort | uniq -c
```

Three consecutive runs: 7/7 PASS, `EQUIVALENT: 7 steps, 731 compared facts`,
with `compare.sh`'s ignore list **unchanged**.

---

## Tests

```bash
cd /opt/hybrid-shim && node --test test/      # 47/47, no npm dependencies
```

`test/idgen.test.js` and `test/boundary.test.js` redirect `GAP_LOG` to a temp
dir, so running the suite never writes to the real `gaps.jsonl`.

## Gap log

`/opt/hybrid-shim/gaps.jsonl` (a symlink into the container's bind mount at
`gaps/`). One JSON object per line. Every invented default, dropped field or
faked response key lands here — including ones recorded per request, so a
dependency that is quietly lossy shows up as volume, not silence.
