// One-shot: record the gaps that arise when CCRS's 2.x boundary lookups are
// re-pointed at the DIGIT 3.0 boundary service. These cannot be recorded by the
// translator, because they happen once, out of band, at seeding/switchover
// time. Run once per fresh v3 boundary store.
//
// Idempotent: an entry whose (dep, field, action) triple is already in the log
// is skipped, so re-running does not inflate the record.
process.env.GAP_LOG = process.env.GAP_LOG || '/opt/hybrid-shim/gaps.jsonl';
const fs = require('node:fs');
const gaplog = require('/opt/hybrid-shim/src/gaplog');

const seen = new Set();
try {
  for (const line of fs.readFileSync(gaplog.logPath(), 'utf8').split('\n')) {
    if (!line.trim()) continue;
    const o = JSON.parse(line);
    seen.add([o.dep, o.field, o.action].join('\u0000'));
  }
} catch {
  /* no log yet */
}

function once(entry) {
  const key = [entry.dep, entry.field, entry.action].join('\u0000');
  if (seen.has(key)) {
    process.stdout.write(`skip (already recorded): ${entry.field} / ${entry.action}\n`);
    return;
  }
  seen.add(key);
  gaplog.record(entry);
  process.stdout.write(`recorded: ${entry.field} / ${entry.action}\n`);
}

const DEP = 'boundary-service';

once({
  dep: DEP,
  field: 'EGOV_BOUNDARY_HOST',
  action: 'unmapped',
  detail:
    'STRUCTURAL: one env var, TWO 2.x endpoints. EGOV_BOUNDARY_HOST is the base for both ' +
    'egov.boundary.search.url (/boundary-service/boundary/_search, used by ' +
    'ServiceRequestValidator.validateBoundary to check a complaint locality) AND ' +
    'egov.boundary.relationship.search.url (/boundary-service/boundary-relationships/_search, used ' +
    'by BoundaryHierarchyExpander to expand an employee HRMS jurisdiction into the set of boundary ' +
    'codes the employee may see). A dependency cannot be re-pointed at v3 one API at a time: moving ' +
    'the variable moves both, so a shim MUST cover every endpoint the host serves or it will answer ' +
    'the wrong envelope to the one it forgot. Observed exactly that: the first switchover registered ' +
    'one translator for the whole /boundary-service prefix, the relationship call came back as a ' +
    'boundary envelope with no "TenantBoundary" key, the employee jurisdiction expanded to the empty ' +
    'set, and the final employee search returned ZERO complaints with HTTP 200 and no error anywhere ' +
    'in CCRS. The translator now dispatches on the sub-path and throws (502 SHIM_ERROR) on any third ' +
    '/boundary-service/* path rather than guessing.',
});

once({
  dep: DEP,
  field: 'BoundaryHierarchyExpander cache/fallback',
  action: 'unmapped',
  detail:
    'A boundary translation regression is INVISIBLE for up to 30 minutes and never raises an error. ' +
    'BoundaryHierarchyExpander caches each (tenantId, hierarchyType, code) expansion in a Caffeine ' +
    'cache with expireAfterWrite = 30 minutes, and on ANY lookup failure it logs a warning and falls ' +
    'back to the single unexpanded code. Consequences measured live during this task: (a) a bad ' +
    'expansion cached at 03:42 was still being served at 03:47 even though the translator had been ' +
    'fixed and the shim rebuilt, so the lifecycle stayed red for reasons that no longer existed; ' +
    '(b) "docker compose up -d pgr-services" does NOT clear it - with no config change compose leaves ' +
    'the container running, and only "docker restart digit-pgr-services-1" actually evicts the cache. ' +
    'Anyone measuring a boundary change against the lifecycle must force-restart pgr-services first, ' +
    'or wait out the full 30 minutes, or they will measure the previous state.',
});

once({
  dep: DEP,
  field: 'boundary data migration',
  action: 'unmapped',
  detail:
    'The v3 boundary store was EMPTY - GET /boundary/v3/boundaries returned {"boundary":[]} for every ' +
    'code in tenant pg. Nothing migrates 2.x boundary data into v3, so the hierarchy and boundaries ' +
    'had to be hand-seeded before CCRS could validate a locality through v3 (see ' +
    '/opt/hybrid-shim/seed-boundaries.sh: 1 ADMIN hierarchy definition, 5 boundaries and 5 ' +
    'relationships, transcribed from the live CCRS tables boundary/boundary_relationship for tenant ' +
    'pg). The two stores are now INDEPENDENT COPIES of the same geography with different row uuids ' +
    'and different auditDetails, and nothing reconciles them: a boundary added in 2.x will not appear ' +
    'in v3, and a complaint validated against v3 can name a locality 2.x has never heard of. Only the ' +
    'ADMIN chain that the baseline lifecycle exercises was seeded (PG_STATE > PG_CITYA > PG_CITYA_Z1 ' +
    '> PG_CITYA_B1 > SUN01_LOCALITY); SUN02_LOCALITY, SUN03_LOCALITY and the whole pg.citya tenant ' +
    'exist in 2.x and do NOT exist in v3.',
});

once({
  dep: DEP,
  field: 'codes (array parameter)',
  action: 'unmapped',
  detail:
    "SPEC vs IMPLEMENTATION. digit-specs v3.0.0 boundary.yaml declares GET /boundaries' `codes` as " +
    '"type: array, minItems: 1, style: form, explode: false" - i.e. comma-separated. The running v3 ' +
    'service does not implement that: ?codes=PG_CITYA,SUN01_LOCALITY returns {"boundary":[]}, and so ' +
    'does the repeated-parameter form ?codes=PG_CITYA&codes=SUN01_LOCALITY, while either code alone ' +
    'returns its row. The raw query-string value is matched as ONE code. 2.x serves the same ' +
    'comma-separated request correctly and returns both boundaries (verified live). CCRS\'s ' +
    'validateBoundary sends exactly one code, so the complaint lifecycle does not expose this, but ' +
    'any 2.x caller doing a multi-code boundary lookup cannot be served by v3 today.',
});

once({
  dep: DEP,
  field: 'Geometry.coordinates',
  action: 'unmapped',
  detail:
    "SPEC vs IMPLEMENTATION, second instance. boundary.yaml's Geometry schema allows type Point but " +
    'types `coordinates` as a three-deep array of numbers (the Polygon shape), which cannot express a ' +
    'Point\'s flat [lon, lat]. The running service accepts and returns the flat form regardless - ' +
    'SUN01_LOCALITY round-trips as {"type":"Point","coordinates":[74.87155,31.63089]}, byte-identical ' +
    'to 2.x. Recorded because the published contract and the deployed service disagree: anything ' +
    'generated from the spec (client stubs, validators) would reject data the service itself emits.',
});

once({
  dep: DEP,
  field: 'TenantBoundary[] duplicate root',
  action: 'dropped',
  detail:
    'The live 2.x boundary-relationships search returns the SAME root tree TWICE inside ' +
    'TenantBoundary[0].boundary (one element per matching relationship row); v3 returns it once. The ' +
    'shim does not reproduce the duplication. Harmless for CCRS - BoundaryHierarchyExpander collects ' +
    'codes into a LinkedHashSet, so duplicates were being discarded anyway - but it is a visible ' +
    'difference between the two replies and is recorded rather than passed over.',
});
