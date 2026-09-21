// test/empmap.test.js - the employee map, and the two decisions in it that are
// not obvious: it refuses an ambiguous map rather than picking, and it never
// learns from traffic.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

function withMap(doc, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'empmap-'));
  const file = path.join(dir, 'empmap.json');
  if (doc !== undefined) fs.writeFileSync(file, typeof doc === 'string' ? doc : JSON.stringify(doc));
  const prev = process.env.EMP_MAP;
  process.env.EMP_MAP = file;
  delete require.cache[require.resolve('../src/empmap')];
  const empmap = require('../src/empmap');
  try {
    return fn(empmap, file);
  } finally {
    if (prev === undefined) delete process.env.EMP_MAP;
    else process.env.EMP_MAP = prev;
    delete require.cache[require.resolve('../src/empmap')];
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

const ONE = {
  version: 1,
  entries: {
    'uuid-2x-a': {
      employeeId: 'v3-emp-a',
      employeeCode: 'PGGRO1',
      tenantId: 'pg',
      migratedName: null,
      migratedRoles: null,
      recordedAt: '2026-09-21T00:00:00.000Z',
      source: 'seed-empmap.js',
    },
  },
};

test('an absent map is empty, not an error - the shim must still serve citizens', () => {
  withMap(undefined, (empmap) => {
    assert.strictEqual(empmap.size(), 0);
    assert.strictEqual(empmap.lookup('uuid-2x-a'), null);
  });
});

test('lookup and reverse agree', () => {
  withMap(ONE, (empmap) => {
    assert.strictEqual(empmap.lookup('uuid-2x-a').employeeId, 'v3-emp-a');
    assert.strictEqual(empmap.reverse('v3-emp-a').uuid2x, 'uuid-2x-a');
    assert.strictEqual(empmap.lookup('nobody'), null);
    assert.strictEqual(empmap.reverse('nobody'), null);
  });
});

test('unparseable map THROWS - "which employee is this" has no safe default', () => {
  withMap('{not json', (empmap) => {
    assert.throws(() => empmap.lookup('uuid-2x-a'), /not valid JSON/);
  });
});

test('wrong version throws rather than being read optimistically', () => {
  withMap({ version: 99, entries: {} }, (empmap) => {
    assert.throws(() => empmap.size(), /version 99/);
  });
});

test('an entry without an employeeId throws', () => {
  withMap({ version: 1, entries: { 'uuid-2x-a': { employeeCode: 'PGGRO1' } } }, (empmap) => {
    assert.throws(() => empmap.size(), /has no employeeId/);
  });
});

test('two 2.x uuids on one v3 employee throws - a reverse lookup must not be a coin flip', () => {
  withMap(
    {
      version: 1,
      entries: {
        'uuid-2x-a': { employeeId: 'v3-emp-a' },
        'uuid-2x-b': { employeeId: 'v3-emp-a' },
      },
    },
    (empmap) => {
      assert.throws(() => empmap.size(), /onto the same v3 employee/);
    }
  );
});

test('put is atomic, re-readable, and refuses an entry it cannot key', () => {
  withMap({ version: 1, entries: {} }, (empmap, file) => {
    empmap.put({ uuid2x: 'uuid-2x-a', employeeId: 'v3-emp-a', employeeCode: 'PGGRO1', tenantId: 'pg' });
    assert.strictEqual(empmap.lookup('uuid-2x-a').employeeCode, 'PGGRO1');
    assert.ok(empmap.lookup('uuid-2x-a').recordedAt, 'provenance timestamp is written');
    assert.deepStrictEqual(fs.readdirSync(path.dirname(file)), ['empmap.json'], 'no tmp file left behind');
    assert.throws(() => empmap.put({ employeeId: 'x' }), /uuid2x required/);
    assert.throws(() => empmap.put({ uuid2x: 'y' }), /employeeId required/);
  });
});

test('a second put for the same uuid overwrites in place, so re-running the seeder is idempotent', () => {
  withMap({ version: 1, entries: {} }, (empmap) => {
    empmap.put({ uuid2x: 'uuid-2x-a', employeeId: 'v3-emp-a', tenantId: 'pg' });
    empmap.put({ uuid2x: 'uuid-2x-a', employeeId: 'v3-emp-a', tenantId: 'pg', migratedName: 'PG Grievance Officer' });
    assert.strictEqual(empmap.size(), 1);
    assert.strictEqual(empmap.lookup('uuid-2x-a').migratedName, 'PG Grievance Officer');
  });
});

test('a changed file on disk is picked up (mtime cache invalidates)', () => {
  withMap(ONE, (empmap, file) => {
    assert.strictEqual(empmap.size(), 1);
    const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
    doc.entries['uuid-2x-b'] = { employeeId: 'v3-emp-b', tenantId: 'pg' };
    fs.writeFileSync(file, JSON.stringify(doc));
    assert.strictEqual(empmap.size(), 2);
  });
});
