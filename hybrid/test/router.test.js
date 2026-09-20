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

function stub() {
  return { toV3: () => ({}), toV2: () => ({}) };
}

test('a trailing slash registers and resolves as the same prefix', () => {
  const t = stub();
  router.register('/egov-slash/', t);
  assert.ok(router.registered().includes('/egov-slash'), 'the trailing slash is folded away');
  assert.ok(!router.registered().includes('/egov-slash/'), 'no second key for the slashed spelling');
  assert.strictEqual(router.resolve('/egov-slash/id/_generate'), t);
  assert.strictEqual(router.resolve('/egov-slash'), t);
});

test('re-registering a prefix throws instead of silently overwriting', () => {
  const first = stub();
  const second = stub();
  router.register('/egov-collide', first);
  assert.throws(() => router.register('/egov-collide', second), /already registered/);
  assert.strictEqual(router.resolve('/egov-collide/x'), first, 'the first translator must survive');
});

test('the slashed spelling collides with the bare one, loudly', () => {
  const first = stub();
  const second = stub();
  router.register('/egov-both', first);
  assert.throws(() => router.register('/egov-both/', second), /already registered/);
  assert.strictEqual(router.resolve('/egov-both/x'), first, 'the first translator must survive');
});

test('resolveEntry hands back the prefix that matched', () => {
  const t = stub();
  router.register('/egov-entry', t);
  const match = router.resolveEntry('/egov-entry/deep/path');
  assert.strictEqual(match.translator, t);
  assert.strictEqual(match.prefix, '/egov-entry');
  assert.strictEqual(router.resolveEntry('/nope'), null);
});
