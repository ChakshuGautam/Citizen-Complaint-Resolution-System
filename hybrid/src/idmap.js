// src/idmap.js
//
// THE ONLY STATEFUL THING THIS SHIM OWNS.
//
// Everything else in src/ is a pure function of one inbound 2.x request: the
// shim reads a request, rewrites it, calls v3, rewrites the answer, and
// remembers nothing. This file is the single deliberate exception, and it
// exists because ONE fact cannot be derived from any request - the
// correspondence between a 2.x `egov-user` uuid (which CCRS mints at login and
// stamps onto every complaint as `accountId`, EnrichmentService:64) and the
// uuid of the same person in the DIGIT 3.0 individual registry.
//
// It cannot be derived because the deployed v3 individual service refuses both
// ways of expressing it (verified live, see the findings doc §6.2):
//   - a create that supplies `id` / `individualId` has it SILENTLY REPLACED by
//     a server-minted uuid (201, wrong id);
//   - the `userId` field that could have held the foreign key is stored, but
//     its search filter is ACCEPTED AND IGNORED - ?userId=<anything> returns
//     every individual in the tenant with HTTP 200.
// So the correspondence has to live somewhere the shim controls. A JSON file.
//
// It is a POC store, and its shortcomings are the point of the experiment:
//   - single writer, no locking beyond an atomic tmp+rename;
//   - no transactions with either registry, so a crash between the v3 create
//     and the write here LEAKS an individual the map does not know about;
//   - it is a second source of truth for identity, which is exactly the thing
//     a migration is supposed to end up with FEWER of.
// In a real migration this is not a file: see the report.
//
// Shape on disk:
//   {
//     "version": 1,
//     "entries": {
//       "<2.x egov-user uuid>": {
//         "individualId": "<v3 individual uuid>",
//         "individualHumanId": "IND-xxxxxxxx" | null,
//         "tenantId": "pg",
//         "mobileNumber": "9999009999" | null,
//         "keycloakSub": null,          // no Keycloak user was created
//         "source": "seed-idmap.js",
//         "recordedAt": "<ISO>"
//       }
//     }
//   }
//
// Node standard library only.
const fs = require('node:fs');
const path = require('node:path');

const VERSION = 1;

function filePath() {
  return process.env.ID_MAP || '/opt/hybrid-shim/state/idmap.json';
}

let cache = null;
let cacheKey = '';

function emptyMap() {
  return { version: VERSION, entries: {}, byIndividual: new Map(), file: filePath() };
}

// A malformed map is NOT recoverable by guessing: every entry here decides
// which human being an answer is about, so a file we cannot parse must stop
// the request loudly rather than degrade into "no mapping known".
function load() {
  const file = filePath();
  let st;
  try {
    st = fs.statSync(file);
  } catch {
    cache = emptyMap();
    cacheKey = 'absent:' + file;
    return cache;
  }
  const key = `${file}:${st.mtimeMs}:${st.size}`;
  if (cache && key === cacheKey) return cache;

  const raw = fs.readFileSync(file, 'utf8');
  let doc;
  try {
    doc = JSON.parse(raw);
  } catch (err) {
    throw new Error(`idmap: ${file} is not valid JSON (${err.message}) - refusing to answer identity questions from a map I cannot read`);
  }
  if (!doc || typeof doc !== 'object' || doc.version !== VERSION) {
    throw new Error(`idmap: ${file} has version ${doc && doc.version} (expected ${VERSION})`);
  }
  const entries = doc.entries && typeof doc.entries === 'object' ? doc.entries : {};
  const byIndividual = new Map();
  for (const [uuid2x, entry] of Object.entries(entries)) {
    if (!entry || typeof entry !== 'object' || typeof entry.individualId !== 'string' || !entry.individualId) {
      throw new Error(`idmap: ${file} entry ${uuid2x} has no individualId`);
    }
    if (byIndividual.has(entry.individualId)) {
      // Two 2.x users pointing at one individual would make the reverse
      // substitution a coin flip. Better to refuse than to pick.
      throw new Error(
        `idmap: ${file} maps two 2.x uuids (${byIndividual.get(entry.individualId)}, ${uuid2x}) onto the same individual ${entry.individualId}`
      );
    }
    byIndividual.set(entry.individualId, uuid2x);
  }
  cache = { version: doc.version, entries, byIndividual, file };
  cacheKey = key;
  return cache;
}

// lookup(2.x uuid) -> entry | null
function lookup(uuid2x) {
  if (typeof uuid2x !== 'string' || !uuid2x) return null;
  const m = load();
  return Object.prototype.hasOwnProperty.call(m.entries, uuid2x) ? m.entries[uuid2x] : null;
}

// reverse(v3 individual id) -> {uuid2x, entry} | null
function reverse(individualId) {
  if (typeof individualId !== 'string' || !individualId) return null;
  const m = load();
  const uuid2x = m.byIndividual.get(individualId);
  return uuid2x ? { uuid2x, entry: m.entries[uuid2x] } : null;
}

// put({uuid2x, individualId, ...}) - atomic tmp+rename, re-reads first so a
// concurrent seeder's entry is not lost. Used by seed-idmap.js, never by a
// request path: the shim does not learn identities from traffic, because a
// guess written here would be indistinguishable from a migrated fact.
function put(entry) {
  const { uuid2x, individualId } = entry || {};
  if (typeof uuid2x !== 'string' || !uuid2x) throw new TypeError('idmap.put: uuid2x required');
  if (typeof individualId !== 'string' || !individualId) throw new TypeError('idmap.put: individualId required');
  const file = filePath();
  let doc = { version: VERSION, entries: {} };
  try {
    doc = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    /* first write */
  }
  if (!doc.entries || typeof doc.entries !== 'object') doc.entries = {};
  doc.version = VERSION;
  const rest = Object.assign({}, entry);
  delete rest.uuid2x;
  doc.entries[uuid2x] = Object.assign({ recordedAt: new Date().toISOString() }, rest);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp.${process.pid}`;
  fs.writeFileSync(tmp, JSON.stringify(doc, null, 2) + '\n');
  fs.renameSync(tmp, file);
  cache = null;
  cacheKey = '';
  return doc.entries[uuid2x];
}

function size() {
  return Object.keys(load().entries).length;
}

module.exports = { lookup, reverse, put, size, filePath, VERSION };
