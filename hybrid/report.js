// report.js — summarise /opt/hybrid-shim/gaps.jsonl by dependency.
//
// Read-only. It never appends to the log.
//
// The log is APPEND-ONLY by construction (gaplog.record only ever appends), so
// a claim that was later corrected is still in the file above its own
// correction — see the "SUPERSEDED" section this prints. Rows are therefore
// keyed on (dep, field, action) for counting, and the DETAIL shown is always
// the LAST one recorded for that key. Reading the file top-to-bottom and
// stopping at the first match gives you a retracted claim.
//
//   node /opt/hybrid-shim/report.js [path-to-gaps.jsonl]

const fs = require('node:fs');

const LOG = process.argv[2] || process.env.GAP_LOG || '/opt/hybrid-shim/gaps.jsonl';
const DETAIL_CHARS = Number(process.env.DETAIL_CHARS || 240);

const lines = fs.readFileSync(LOG, 'utf8').split('\n').filter(Boolean);
const entries = [];
const malformed = [];
lines.forEach((line, i) => {
  try {
    const o = JSON.parse(line);
    o.__line = i + 1;
    entries.push(o);
  } catch (err) {
    malformed.push({ line: i + 1, err: err.message });
  }
});

const cell = (s) => String(s === undefined || s === null ? '' : s).replace(/\|/g, '\\|').replace(/\s+/g, ' ');
const clip = (s, n) => (s.length > n ? s.slice(0, n - 1) + '…' : s);

// (dep, field, action) -> {count, first, last, detail}
const rows = new Map();
for (const g of entries) {
  const key = [g.dep, g.field, g.action].join('\u0000');
  const r = rows.get(key) || { dep: g.dep, field: g.field, action: g.action, count: 0, first: g.__line, last: g.__line, detail: '' };
  r.count += 1;
  r.last = g.__line;
  if (g.detail) r.detail = g.detail; // last wins
  rows.set(key, r);
}

const byDep = new Map();
for (const r of rows.values()) {
  if (!byDep.has(r.dep)) byDep.set(r.dep, []);
  byDep.get(r.dep).push(r);
}

const ACTION_ORDER = { unmapped: 0, faked: 1, dropped: 2, defaulted: 3 };

console.log(`# Gap report\n`);
console.log(`Source: \`${LOG}\` — ${entries.length} entries, ${rows.size} distinct (dep, field, action) triples,`);
console.log(`${byDep.size} dependencies.${malformed.length ? ` ${malformed.length} malformed line(s).` : ''}\n`);
console.log(`Counts are CALL FREQUENCIES, not distinct findings: per-request entries are written on every`);
console.log(`translated call. The detail shown is the LAST one recorded for that triple.\n`);

// Fields whose action changed over time — the log's one real ambiguity.
const actionsByField = new Map();
for (const g of entries) {
  const k = [g.dep, g.field].join('\u0000');
  if (!actionsByField.has(k)) actionsByField.set(k, new Set());
  actionsByField.get(k).add(g.action);
}
const conflicting = [...actionsByField.entries()].filter(([, s]) => s.size > 1);
const superseding = entries.filter((g) => /SUPERSEDE/i.test(g.detail || ''));

if (conflicting.length || superseding.length) {
  console.log(`## SUPERSEDED — read the LAST entry, not the first\n`);
  if (superseding.length) {
    console.log(`Entries that explicitly supersede an earlier claim:\n`);
    console.log(`| line | ts | dep | field | action |`);
    console.log(`|---|---|---|---|---|`);
    for (const g of superseding) {
      console.log(`| ${g.__line} | ${cell(g.ts)} | ${cell(g.dep)} | ${cell(g.field)} | ${cell(g.action)} |`);
    }
    console.log('');
  }
  if (conflicting.length) {
    console.log(`Fields recorded under more than one action (the earlier ones are stale):\n`);
    for (const [k, s] of conflicting) {
      const [dep, field] = k.split('\u0000');
      console.log(`- \`${dep}\` | \`${field}\` -> ${[...s].join(', ')}`);
    }
    console.log('');
  }
}

for (const dep of [...byDep.keys()].sort()) {
  const list = byDep.get(dep).sort((a, b) => {
    const d = (ACTION_ORDER[a.action] ?? 9) - (ACTION_ORDER[b.action] ?? 9);
    return d !== 0 ? d : b.count - a.count;
  });
  const total = list.reduce((n, r) => n + r.count, 0);
  console.log(`## ${dep} — ${list.length} distinct, ${total} entries\n`);
  console.log(`| field | action | count | lines | detail |`);
  console.log(`|---|---|---:|---|---|`);
  for (const r of list) {
    const lineRef = r.first === r.last ? `L${r.first}` : `L${r.first}-${r.last}`;
    console.log(`| \`${cell(r.field)}\` | ${cell(r.action)} | ${r.count} | ${lineRef} | ${clip(cell(r.detail), DETAIL_CHARS)} |`);
  }
  console.log('');
}

if (malformed.length) {
  console.log(`## Malformed lines\n`);
  for (const m of malformed) console.log(`- line ${m.line}: ${m.err}`);
}
