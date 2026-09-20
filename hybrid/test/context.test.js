// test/context.test.js
const test = require('node:test');
const assert = require('node:assert');
const context = require('../src/context');

test('maps RequestInfo userInfo to v3 headers', () => {
  const body = { RequestInfo: { userInfo: { uuid: 'u-1', tenantId: 'mz' } } };
  assert.deepStrictEqual(context.toHeaders(body), {
    'X-Tenant-ID': 'mz',
    'X-User-ID': 'u-1',
  });
});

test('falls back to RequestInfo.tenantId', () => {
  const body = { RequestInfo: { tenantId: 'mz', userInfo: { uuid: 'u-2' } } };
  assert.strictEqual(context.toHeaders(body)['X-Tenant-ID'], 'mz');
});
