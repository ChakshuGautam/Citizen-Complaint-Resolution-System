// test/router.test.js
const test = require('node:test');
const assert = require('node:assert');
const router = require('../src/router');

test('unknown path resolves to null', () => {
  assert.strictEqual(router.resolve('/nope/_search'), null);
});

test('registered prefix wins', () => {
  const t = { toV3: () => ({}), toV2: () => ({}) };
  router.register('/egov-idgen', t);
  assert.strictEqual(router.resolve('/egov-idgen/id/_generate'), t);
});
