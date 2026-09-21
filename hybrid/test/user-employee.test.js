// test/user-employee.test.js - the employee ROUTE in the user translator.
//
// The single most important assertion here is the one about roles: resolving
// an employee's IDENTITY does not resolve the assignee, because what
// ComplaintDomainEventService calls "assigneeName" is actually
// roles.get(0).getName(), and the v3 employee model has no roles at all.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

function withTranslator(entries, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'empsearch-'));
  const file = path.join(dir, 'empmap.json');
  fs.writeFileSync(file, JSON.stringify({ version: 1, entries }));
  const prevMap = process.env.EMP_MAP;
  const prevBase = process.env.V3_EMPLOYEE_BASE;
  const prevGap = process.env.GAP_LOG;
  process.env.EMP_MAP = file;
  process.env.V3_EMPLOYEE_BASE = 'http://d3-employee.test:8080';
  process.env.GAP_LOG = path.join(dir, 'gaps.jsonl');
  for (const m of ['../src/empmap', '../src/idmap', '../src/gaplog', '../src/translators/user']) {
    delete require.cache[require.resolve(m)];
  }
  const user = require('../src/translators/user');
  try {
    return fn(user);
  } finally {
    if (prevMap === undefined) delete process.env.EMP_MAP; else process.env.EMP_MAP = prevMap;
    if (prevBase === undefined) delete process.env.V3_EMPLOYEE_BASE; else process.env.V3_EMPLOYEE_BASE = prevBase;
    if (prevGap === undefined) delete process.env.GAP_LOG; else process.env.GAP_LOG = prevGap;
    for (const m of ['../src/empmap', '../src/idmap', '../src/gaplog', '../src/translators/user']) {
      delete require.cache[require.resolve(m)];
    }
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

const GRO = {
  '21fc9b81': {
    employeeId: 'v3-emp-gro',
    employeeCode: 'PGGRO1',
    tenantId: 'pg',
    migratedName: null,
    migratedRoles: null,
    recordedAt: '2026-09-21T00:00:00.000Z',
    source: 'seed-empmap.js',
  },
};

function req(body) {
  return Object.assign({ RequestInfo: { userInfo: { uuid: '21fc9b81', tenantId: 'pg' } } }, body);
}

test('an employee uuid with no userType routes to d3-employee by the BY-ID PATH form', () => {
  withTranslator(GRO, (user) => {
    const out = user.employeeSearchToV3(req({ tenantId: 'pg' }), ['21fc9b81'], 'pg');
    assert.strictEqual(out.method, 'GET');
    assert.strictEqual(out.path, 'http://d3-employee.test:8080/employee/v3/employees/v3-emp-gro');
  });
});

test('a CITIZEN-pinned search is NOT answered from the employee registry', () => {
  withTranslator(GRO, (user) => {
    const out = user.employeeSearchToV3(req({ tenantId: 'pg', userType: 'CITIZEN' }), ['21fc9b81'], 'pg');
    assert.strictEqual(out, null, '2.x would answer this with an empty list, and so must the shim');
  });
});

test('an unmapped uuid falls through unchanged - the citizen path is untouched', () => {
  withTranslator(GRO, (user) => {
    assert.strictEqual(user.employeeSearchToV3(req({ tenantId: 'pg' }), ['f1a94c66'], 'pg'), null);
  });
});

test('a tenant mismatch refuses the entry instead of leaking across tenants', () => {
  withTranslator(GRO, (user) => {
    assert.strictEqual(user.employeeSearchToV3(req({ tenantId: 'mz' }), ['21fc9b81'], 'mz'), null);
  });
});

test('two employees in one search throws - v3 ignores ?id= on the list endpoint', () => {
  const two = Object.assign({}, GRO, { 'b-uuid': { employeeId: 'v3-emp-b', employeeCode: 'PGLME2', tenantId: 'pg' } });
  withTranslator(two, (user) => {
    assert.throws(
      () => user.employeeSearchToV3(req({ tenantId: 'pg' }), ['21fc9b81', 'b-uuid'], 'pg'),
      /only the by-id path form filters/
    );
  });
});

test('a search mixing an employee with a non-employee throws rather than half-answering', () => {
  withTranslator(GRO, (user) => {
    assert.throws(
      () => user.employeeSearchToV3(req({ tenantId: 'pg' }), ['21fc9b81', 'f1a94c66'], 'pg'),
      /splits the two registries/
    );
  });
});

test('THE HEADLINE: a v3 employee answers with roles: [] - so getFirstRoleNameByUuid still returns null', () => {
  withTranslator(GRO, (user) => {
    const r = req({ tenantId: 'pg' });
    user.employeeSearchToV3(r, ['21fc9b81'], 'pg');
    const out = user.employeeToV2(
      {
        status: 200,
        json: {
          id: 'v3-emp-gro',
          code: 'PGGRO1',
          userId: '21fc9b81',
          status: 'EMPLOYED',
          employeeType: 'PERMANENT',
          dateOfAppointment: '2026-09-21T02:53:36.030938Z',
          department: 'DEPT_3',
          designation: 'COMM',
          isActive: true,
        },
      },
      r
    );
    const u = out.user[0];
    assert.strictEqual(u.uuid, '21fc9b81', 'the 2.x uuid is restored, not the v3 employee id');
    assert.strictEqual(u.userName, 'PGGRO1');
    assert.strictEqual(u.type, 'EMPLOYEE');
    assert.strictEqual(u.tenantId, 'pg');
    assert.strictEqual(u.active, true);
    assert.deepStrictEqual(u.roles, [], 'v3 employee_v3 has NO roles column; roles live in Keycloak');
    assert.strictEqual(u.name, null, 'v3 employee_v3 has NO name column; it points at an individual');
    assert.ok(/^\d{2}-\d{2}-\d{4} /.test(u.createdDate), 'createdDate is mandatory: parseResponse NPEs without it');
  });
});

test('with roles carried by the map, the SAME lookup produces a real role name', () => {
  const withRoles = {
    '21fc9b81': Object.assign({}, GRO['21fc9b81'], {
      migratedName: 'PG Grievance Officer',
      migratedRoles: [
        { code: 'EMPLOYEE', name: 'Employee', tenantId: 'pg' },
        { code: 'GRO', name: 'Complaint Assessor', tenantId: 'pg' },
      ],
    }),
  };
  withTranslator(withRoles, (user) => {
    const r = req({ tenantId: 'pg' });
    user.employeeSearchToV3(r, ['21fc9b81'], 'pg');
    const u = user.employeeToV2({ status: 200, json: { id: 'v3-emp-gro', code: 'PGGRO1', isActive: true } }, r).user[0];
    assert.strictEqual(u.roles[0].name, 'Employee', 'this is what CCRS stamps as assigneeName');
    assert.strictEqual(u.name, 'PG Grievance Officer');
  });
});

test('a 404 from v3 stays an error - a broken map is not "no such user"', () => {
  withTranslator(GRO, (user) => {
    const r = req({ tenantId: 'pg' });
    user.employeeSearchToV3(r, ['21fc9b81'], 'pg');
    const out = user.employeeToV2({ status: 404, json: [{ code: 'NOT_FOUND', message: 'nope' }], raw: '' }, r);
    assert.ok(out.Errors && out.Errors.length, 'an empty user list would be a MEANINGFUL and wrong answer');
  });
});
