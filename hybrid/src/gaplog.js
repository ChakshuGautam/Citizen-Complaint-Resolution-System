// src/gaplog.js
//
// Append-only record of every place the shim had to invent, drop or fake
// something. A silent default is a bug: if a translator cannot carry a field
// across the 2.x <-> v3 boundary faithfully, it MUST say so here.
//
// One JSON object per line, each with an ISO timestamp.
const fs = require('node:fs');
const path = require('node:path');

const ACTIONS = ['dropped', 'defaulted', 'faked', 'unmapped'];

function logPath() {
  return process.env.GAP_LOG || '/opt/hybrid-shim/gaps.jsonl';
}

// record({dep, field, action, detail})
//   dep    - the 2.x dependency being shimmed, e.g. 'egov-idgen', 'router'
//   field  - the field or path the gap is about
//   action - one of ACTIONS
//   detail - free text; why, and what value was used instead (optional)
function record(entry) {
  const { dep, field, action, detail } = entry || {};
  if (!ACTIONS.includes(action)) {
    throw new TypeError(
      `gaplog.record: invalid action ${JSON.stringify(action)} (expected one of ${ACTIONS.join(', ')})`
    );
  }
  const line = {
    ts: new Date().toISOString(),
    dep: dep || 'unknown',
    field: field === undefined || field === null ? '' : String(field),
    action,
  };
  if (detail !== undefined) line.detail = detail;

  const file = logPath();
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.appendFileSync(file, JSON.stringify(line) + '\n');
  } catch (err) {
    // Never fail a request because the gap log is unwritable - but be loud.
    process.stderr.write(`gaplog: could not write ${file}: ${err.message}\n`);
  }
  return line;
}

module.exports = { record, logPath, ACTIONS };
