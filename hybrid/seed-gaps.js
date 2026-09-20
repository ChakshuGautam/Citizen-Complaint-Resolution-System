#!/usr/bin/env node
// seed-gaps.js
//
// Writes the gaps that were already known before the shim ran a single
// request - discovered while building the 2.x baseline in Task 1. Idempotent:
// re-running it does NOT de-duplicate, so run it once per fresh gaps.jsonl.
//
//   GAP_LOG=/opt/hybrid-shim/gaps/gaps.jsonl node seed-gaps.js
const gaplog = require('./src/gaplog');

const seeds = [
  {
    dep: 'egov-user',
    field: 'RequestInfo.userInfo.password',
    action: 'faked',
    detail:
      'Baseline (Task 1, tenant pg): citizen authentication accepts ANY password. ' +
      'The 2.x stack runs in OTP mode with OTP enforcement off, so /user/oauth/token ' +
      'returns a token regardless of the credential presented. Nothing in the baseline ' +
      'actually verifies a citizen credential.',
  },
  {
    dep: 'egov-user',
    field: 'otp',
    action: 'dropped',
    detail:
      'The OTP supplied at login is never validated in the baseline stack. If a ' +
      'v3-backed user/identity service enforces credentials or OTP, baseline step 1 ' +
      '(citizen auth) legitimately turns red - that is a real finding, not a shim ' +
      'regression, and the lifecycle comparison must be re-baselined.',
  },
];

for (const s of seeds) {
  process.stdout.write(JSON.stringify(gaplog.record(s)) + '\n');
}
