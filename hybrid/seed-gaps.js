#!/usr/bin/env node
// seed-gaps.js
//
// Writes the gaps that were already known before the shim ran a single
// request - discovered while building the 2.x baseline in Task 1.
//
// Safe to re-run: an entry whose (dep, field, action) triple is already in the
// log is skipped, so this never duplicates seeds into a live gaps.jsonl.
//
//   GAP_LOG=/opt/hybrid-shim/gaps/gaps.jsonl node seed-gaps.js
const fs = require('node:fs');
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

// The identity of a seed is (dep, field, action). The timestamp and the detail
// wording are deliberately NOT part of it, so re-running after an edit to the
// detail text still does not duplicate the entry.
function key(e) {
  return JSON.stringify([e.dep, e.field, e.action]);
}

function existingKeys(file) {
  const seen = new Set();
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (err) {
    if (err.code === 'ENOENT') return seen; // fresh log, nothing to skip
    throw err;
  }
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    try {
      seen.add(key(JSON.parse(line)));
    } catch {
      // A malformed line cannot be matched against; ignore it rather than
      // refusing to seed. The gap log is append-only and hand-inspectable.
    }
  }
  return seen;
}

function main() {
  const file = gaplog.logPath();
  const seen = existingKeys(file);
  let wrote = 0;
  let skipped = 0;

  for (const s of seeds) {
    if (seen.has(key(s))) {
      skipped += 1;
      process.stdout.write(`skip (already present): ${s.dep} ${s.field} ${s.action}\n`);
      continue;
    }
    process.stdout.write(JSON.stringify(gaplog.record(s)) + '\n');
    seen.add(key(s));
    wrote += 1;
  }

  process.stdout.write(`seed-gaps: ${wrote} written, ${skipped} skipped -> ${file}\n`);
}

main();
