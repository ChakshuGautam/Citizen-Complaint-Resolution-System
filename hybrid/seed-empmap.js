// seed-empmap.js - record the correspondence between a 2.x egov-user uuid and
// a DIGIT 3.0 employee record, in src/empmap.js's file.
//
// This is the HRMS half of the migration, shrunk to one row. Run on the host:
//
//   EMP_MAP=/opt/hybrid-shim/state/empmap.json \
//   V3_BASE=http://localhost:8109 \
//     node /opt/hybrid-shim/seed-empmap.js \
//       --uuid 21fc9b81-... --tenant pg --code PGGRO1 \
//       [--name "PG Grievance Officer"] [--roles EMPLOYEE:Employee,GRO:Complaint Assessor]
//
// TWO THINGS THIS SCRIPT CANNOT DO, AND WHY THEY MATTER MORE THAN WHAT IT CAN:
//
//  1. IT CANNOT CREATE THE v3 EMPLOYEE. POST /employee/v3/employees validates
//     the supplied userID by calling Keycloak - and the deployed d3-employee
//     build resolves Keycloak from KEYCLOAK_BASE_URL, which is NOT SET in this
//     stack's compose file and therefore falls back to the binary's compiled-in
//     default: https://digit-lts.digit.org/keycloak, the DIGIT LTS demo
//     environment. Verified from the container's own log:
//
//       "failed to validate user ID: keycloak service request failed:
//        Get https://digit-lts.digit.org/keycloak/admin/realms/PG/users/<id>:
//        dial tcp: lookup digit-lts.digit.org: no such host"
//
//     Note the realm name: PG - the tenant code, upper-cased. So a v3 employee
//     CANNOT EXIST without a Keycloak user in a realm named after its tenant,
//     and this box has one Keycloak user (admin, realm master) and no tenant
//     realms. The POC therefore inserts the employee_v3 rows the API would have
//     written (seed-employees.sh) and this script only records the mapping.
//
//  2. IT CANNOT FIND AN EMPLOYEE BY ANYTHING. GET /employee/v3/employees
//     accepts ?id=, ?code= and ?userId= and IGNORES ALL THREE - each returns
//     every employee in the tenant, HTTP 200. Only ?isActive= is applied. So
//     the seeder LISTS the tenant and matches client-side on code, which is
//     fine for a migration job (it may page) and impossible for the request
//     path (it may not).
//
// Node standard library only.
const client = require('./src/client');
const empmap = require('./src/empmap');

function arg(name, required) {
  const i = process.argv.indexOf(`--${name}`);
  const v = i > -1 ? process.argv[i + 1] : undefined;
  if (required && (!v || v.startsWith('--'))) {
    process.stderr.write(`seed-empmap: --${name} is required\n`);
    process.exit(2);
  }
  return v;
}

// "CODE:Human Name,CODE2:Other Name" -> [{code, name, tenantId}]
function parseRoles(spec, tenantId) {
  if (!spec) return null;
  return spec
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const i = s.indexOf(':');
      const code = (i > -1 ? s.slice(0, i) : s).trim();
      const name = i > -1 ? s.slice(i + 1).trim() : code;
      return { code, name, tenantId };
    });
}

async function main() {
  const uuid2x = arg('uuid', true);
  const tenantId = arg('tenant', true);
  const code = arg('code', true);
  const name = arg('name', false) || null;
  const roles = parseRoles(arg('roles', false), tenantId);

  const headers = { 'X-Tenant-ID': tenantId, 'X-User-ID': uuid2x };

  const found = await client.call({
    method: 'GET',
    path: '/employee/v3/employees?size=100&page=1',
    headers,
  });
  if (found.status >= 400) throw new Error(`v3 employee list failed ${found.status}: ${found.raw.slice(0, 400)}`);
  const list = Array.isArray(found.json) ? found.json : [];
  const matches = list.filter((e) => e && e.code === code);
  if (!matches.length) {
    throw new Error(
      `v3 holds no employee with code ${code} in tenant ${tenantId} (${list.length} employees in the tenant). ` +
        'Create it first - see seed-employees.sh and the note above about KEYCLOAK_BASE_URL.'
    );
  }
  if (matches.length > 1) {
    throw new Error(
      `v3 holds ${matches.length} employees with code ${code} in tenant ${tenantId} ` +
        `(${matches.map((e) => e.id).join(', ')}) - refusing to guess which one is the 2.x user. ` +
        'employee_v3 has no unique constraint on (code, tenant_id).'
    );
  }
  const emp = matches[0];

  // A cross-check the request path cannot make: employee_v3.user_id is a free
  // varchar, and the ETL put the 2.x uuid in it. If v3 ever implements the
  // ?userId= filter this map becomes derivable; today it does not, so the
  // mismatch is worth shouting about while we still have both sides in hand.
  if (emp.userId && emp.userId !== uuid2x) {
    throw new Error(
      `v3 employee ${emp.id} (code ${code}) carries userId=${emp.userId}, not the 2.x uuid ${uuid2x}`
    );
  }

  const entry = empmap.put({
    uuid2x,
    employeeId: emp.id,
    employeeCode: emp.code,
    tenantId,
    // Both of these are 2.x facts the v3 employee model has no column for. They
    // are recorded here ONLY so the POC can measure what closing each gap
    // would buy; the translator gap-logs every use of them.
    migratedName: name,
    migratedRoles: roles,
    keycloakSub: null,
    source: 'seed-empmap.js',
  });
  process.stdout.write(JSON.stringify({ map: empmap.filePath(), uuid2x, entry }, null, 2) + '\n');
}

main().catch((err) => {
  process.stderr.write(`seed-empmap: ${err.message}\n`);
  process.exit(1);
});
