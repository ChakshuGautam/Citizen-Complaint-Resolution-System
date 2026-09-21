// seed-idmap.js - put ONE 2.x user into the v3 individual registry and record
// the correspondence in src/idmap.js's file.
//
// This is the "migration" the POC is testing, shrunk to one row. Run on the
// host (v3 Kong is published on :8000):
//
//   ID_MAP=/opt/hybrid-shim/state/idmap.json V3_BASE=http://localhost:8000 \
//     node /opt/hybrid-shim/seed-idmap.js \
//       --uuid f1a94c66-... --tenant pg --mobile 9999009999 --name "Baseline Citizen"
//
// Idempotent: it looks the person up in v3 by mobileNumber first and only
// creates one if the registry has none, so a re-run re-records the SAME
// individual id rather than minting a second person. That is only true because
// mobileNumber happens to be a filter this build actually applies; there is no
// uniqueness constraint behind it (no uniquenessCriteria is configured), so two
// concurrent seeders WOULD create two individuals for one human.
//
// Node standard library only.
const client = require('./src/client');
const idmap = require('./src/idmap');

function arg(name, required) {
  const i = process.argv.indexOf(`--${name}`);
  const v = i > -1 ? process.argv[i + 1] : undefined;
  if (required && (!v || v.startsWith('--'))) {
    process.stderr.write(`seed-idmap: --${name} is required\n`);
    process.exit(2);
  }
  return v;
}

async function main() {
  const uuid2x = arg('uuid', true);
  const tenantId = arg('tenant', true);
  const mobile = arg('mobile', true);
  const name = arg('name', true);
  const gender = process.env.INDIVIDUAL_DEFAULT_GENDER || 'OTHER';

  // v3 needs an acting user on every call. There is no v3 identity for
  // whoever runs this script, so the seeded person acts as their own creator:
  // the audit trail on this row will say it created itself.
  const headers = { 'X-Tenant-ID': tenantId, 'X-User-ID': uuid2x };

  const found = await client.call({
    method: 'GET',
    path: `/individual/v3/individuals?mobileNumber=${encodeURIComponent(mobile)}&size=100&page=1`,
    headers,
  });
  if (found.status >= 400) throw new Error(`v3 search failed ${found.status}: ${found.raw.slice(0, 400)}`);
  const list = (found.json && found.json.individuals) || [];
  if (list.length > 1) {
    throw new Error(
      `v3 already holds ${list.length} individuals with mobileNumber ${mobile} ` +
        `(${list.map((i) => i.id).join(', ')}) - refusing to guess which one is the 2.x user`
    );
  }

  let ind = list[0];
  let action = 'found';
  if (!ind) {
    const parts = String(name).trim().split(/\s+/).filter(Boolean);
    const body = { givenName: parts[0] || 'Citizen', gender, mobileNumber: String(mobile) };
    if (parts.length > 1) body.familyName = parts.slice(1).join(' ');
    const created = await client.call({ method: 'POST', path: '/individual/v3/individuals', headers, body });
    if (created.status >= 400) throw new Error(`v3 create failed ${created.status}: ${created.raw.slice(0, 400)}`);
    ind = created.json;
    action = 'created';
  }

  const entry = idmap.put({
    uuid2x,
    individualId: ind.id,
    individualHumanId: ind.individualId || null,
    tenantId,
    mobileNumber: String(mobile),
    // No Keycloak user was created. CCRS's login is still served by egov-user
    // (kong-gateway validates sessions through its OWN upstream, not through
    // EGOV_USER_HOST), so a Keycloak subject would have nothing to do here -
    // but a real migration needs one, and the field says where it goes.
    keycloakSub: null,
    source: 'seed-idmap.js',
    action,
  });
  process.stdout.write(
    JSON.stringify({ map: idmap.filePath(), uuid2x, action, entry }, null, 2) + '\n'
  );
}

main().catch((err) => {
  process.stderr.write(`seed-idmap: ${err.message}\n`);
  process.exit(1);
});
