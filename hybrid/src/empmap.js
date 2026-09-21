// src/empmap.js
//
// THE SECOND STATEFUL THING THIS SHIM OWNS - and it exists because the first
// one was not enough.
//
// src/idmap.js maps a 2.x `egov-user` uuid onto a DIGIT 3.0 *individual*. That
// unblocked CCRS's citizen, and immediately exposed the next person CCRS asks
// about: the EMPLOYEE. `UserService.getFirstRoleNameByUuid` looked up
// 21fc9b81-9e52-43a5-ac1a-00db84dc2a6a (PGGRO1) three times during one
// assign/resolve run, missed the individual map, got {"user":[]} and silently
// stamped the literal string "NA" onto every outbound complaint domain event.
//
// v3 does NOT keep employees in the individual registry. `d3-employee` is a
// separate service (port 8109, context path /employee) with a separate table
// (`employee_v3`) and a separate id space. So the correspondence CCRS needs is
// a SECOND one, of the same shape but pointing somewhere else:
//
//     2.x egov-user uuid  ->  v3 employee id  (employee_v3.id)
//
// This is a PARALLEL map rather than a `kind` column on idmap.js because the
// two really are different facts about different registries, and because
// idmap.js enforces an invariant - "no two 2.x uuids may share one individual"
// - that has nothing to say about employees. Merging them would have meant
// weakening that check.
//
// WHAT THE MAP HAS TO CARRY, AND WHY (every one of these is a measured v3 gap,
// not a convenience):
//
//   * tenantId   - GET /employee/v3/employees/{id} answers WITHOUT a tenantId
//                  field. The tenant is an inbound header only; it never comes
//                  back. CCRS's User.tenantId has to come from somewhere, and
//                  v3 is not it.
//   * migratedName  - employee_v3 has NO name column. It has `individual_id`,
//                  a pointer at the individual registry, and the shim's
//                  one-request-in/one-request-out contract cannot follow it.
//                  Null unless a migration recorded it.
//   * migratedRoles - employee_v3 has NO roles. v3 keeps roles as KEYCLOAK
//                  REALM ROLES (`keycloak_role` / `user_role_mapping` in the v3
//                  Postgres), and on this box there is exactly ONE Keycloak
//                  user (`admin`, realm `master`) and zero tenant realms. This
//                  field is what a completed realm migration (identity plan
//                  task C2) would have put in Keycloak. When it is populated
//                  the shim says so in the gap log, loudly, every single time:
//                  the roles did not come from DIGIT 3.0.
//
// Shape on disk (EMP_MAP, default /opt/hybrid-shim/state/empmap.json):
//   {
//     "version": 1,
//     "entries": {
//       "<2.x egov-user uuid>": {
//         "employeeId":   "<employee_v3.id>",
//         "employeeCode": "PGGRO1",
//         "tenantId":     "pg",
//         "migratedName":  "PG Grievance Officer" | null,
//         "migratedRoles": [{code,name,tenantId}] | null,
//         "keycloakSub":  null,
//         "source": "seed-empmap.js",
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
  return process.env.EMP_MAP || '/opt/hybrid-shim/state/empmap.json';
}

let cache = null;
let cacheKey = '';

function emptyMap() {
  return { version: VERSION, entries: {}, byEmployee: new Map(), file: filePath() };
}

// Same rule as idmap.js: a map we cannot parse is not "no mapping known", it is
// an unknown answer to "which employee is this", and the honest response to
// that is to stop rather than to hand CCRS somebody else with HTTP 200.
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
    throw new Error(
      `empmap: ${file} is not valid JSON (${err.message}) - refusing to answer employee questions from a map I cannot read`
    );
  }
  if (!doc || typeof doc !== 'object' || doc.version !== VERSION) {
    throw new Error(`empmap: ${file} has version ${doc && doc.version} (expected ${VERSION})`);
  }
  const entries = doc.entries && typeof doc.entries === 'object' ? doc.entries : {};
  const byEmployee = new Map();
  for (const [uuid2x, entry] of Object.entries(entries)) {
    if (!entry || typeof entry !== 'object' || typeof entry.employeeId !== 'string' || !entry.employeeId) {
      throw new Error(`empmap: ${file} entry ${uuid2x} has no employeeId`);
    }
    if (byEmployee.has(entry.employeeId)) {
      throw new Error(
        `empmap: ${file} maps two 2.x uuids (${byEmployee.get(entry.employeeId)}, ${uuid2x}) onto the same v3 employee ${entry.employeeId}`
      );
    }
    byEmployee.set(entry.employeeId, uuid2x);
  }
  cache = { version: doc.version, entries, byEmployee, file };
  cacheKey = key;
  return cache;
}

// lookup(2.x uuid) -> entry | null
function lookup(uuid2x) {
  if (typeof uuid2x !== 'string' || !uuid2x) return null;
  const m = load();
  return Object.prototype.hasOwnProperty.call(m.entries, uuid2x) ? m.entries[uuid2x] : null;
}

// reverse(v3 employee id) -> {uuid2x, entry} | null
function reverse(employeeId) {
  if (typeof employeeId !== 'string' || !employeeId) return null;
  const m = load();
  const uuid2x = m.byEmployee.get(employeeId);
  return uuid2x ? { uuid2x, entry: m.entries[uuid2x] } : null;
}

// put({uuid2x, employeeId, ...}) - atomic tmp+rename. Called ONLY by the
// seeder, never by a request path: an employee identity learned from traffic
// would be a guess indistinguishable from a migrated fact.
function put(entry) {
  const { uuid2x, employeeId } = entry || {};
  if (typeof uuid2x !== 'string' || !uuid2x) throw new TypeError('empmap.put: uuid2x required');
  if (typeof employeeId !== 'string' || !employeeId) throw new TypeError('empmap.put: employeeId required');
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
