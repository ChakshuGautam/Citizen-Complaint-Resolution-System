// One-shot: record the gaps that arise when the CCRS 2.x ID format is
// re-expressed as a DIGIT 3.0 idgen template. These cannot be recorded by the
// translator, because they happen once, out of band, at registration time.
// Run once per fresh v3 idgen template store.
process.env.GAP_LOG = process.env.GAP_LOG || '/opt/hybrid-shim/gaps.jsonl';
const gaplog = require('/opt/hybrid-shim/src/gaplog');

gaplog.record({
  dep: 'egov-idgen',
  field: 'format.[SEQ_EG_PGR_ID]',
  action: 'unmapped',
  detail:
    '2.x [SEQ_EG_PGR_ID] names a SHARED Postgres sequence (seq_eg_pgr_id in the CCRS db, at 5216 on ' +
    '2026-09-20) that any idName may point at. v3 has only {SEQ}, a counter owned by one template; ' +
    'there is no way to point a v3 template at an existing named sequence. The v3 counter for ' +
    'pgr.servicerequestid is therefore INDEPENDENT of seq_eg_pgr_id: the two stacks can no longer ' +
    'hand out numbers from the same pool, and any other 2.x idName sharing SEQ_EG_PGR_ID would drift.',
});

gaplog.record({
  dep: 'egov-idgen',
  field: 'config.sequence.start',
  action: 'defaulted',
  detail:
    'Invented value: start=6000. v3 would default to 1, which risks minting an id identical to one 2.x ' +
    'already issued on the same date (the date is part of the id, so the collision window is one day). ' +
    '6000 was chosen as the next round number above the live 2.x high-water mark seq_eg_pgr_id=5216. ' +
    'Nothing in either system enforces this - it is a hand-picked offset and will be wrong the moment ' +
    'the 2.x sequence passes 6000.',
});

gaplog.record({
  dep: 'egov-idgen',
  field: 'format.[cy:yyyy-MM-dd]',
  action: 'unmapped',
  detail:
    '2.x token syntax [cy:<pattern>] was hand-translated to v3 {DATE:<pattern>} when registering the ' +
    'template. The patterns happen to agree for yyyy-MM-dd (verified: v3 rendered 2026-09-20), but no ' +
    'translation layer exists - every 2.x format string must be re-expressed by hand, and 2.x tokens ' +
    'with no v3 equivalent (e.g. [fy:...] financial year, [tenantid], [city]) have nowhere to go.',
});

gaplog.record({
  dep: 'egov-idgen',
  field: 'MDMS common-masters.IdFormat',
  action: 'unmapped',
  detail:
    'In 2.x the authoritative ID format lives in MDMS (common-masters.IdFormat, per tenant) and ' +
    'egov-idgen prefers it over the format the caller sends - verified live: a call sending ' +
    '"PB-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]" returned PG-PGR-2026-09-20-005216. In v3 the format lives ' +
    'in the idgen template store instead. The two are now separate sources of truth: editing the MDMS ' +
    'entry no longer changes the ids CCRS receives, and nothing detects the divergence.',
});

console.log('recorded 4 template-registration gaps');
