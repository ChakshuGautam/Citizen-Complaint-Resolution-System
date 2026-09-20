# Registers the v3 idgen template that CCRS's pgr-services needs.
#
# Shape is taken from the LIVE 2.x source of truth, not invented:
#   MDMS common-masters.IdFormat @ tenant pg, idname "pgr.servicerequestid"
#     -> "PG-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]"
#   (the 2.x egov-idgen prefers this over the format pgr-services sends,
#    "PB-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]" - verified: a live 2.x call that
#    sent PB-... came back with PG-PGR-2026-09-20-005216)
# Token translation: [cy:yyyy-MM-dd] -> {DATE:yyyy-MM-dd}, [SEQ_EG_PGR_ID] -> {SEQ}.
# padding length 6 / char "0" reproduces the 6-digit zero-padded 2.x counter.
# scope GLOBAL because seq_eg_pgr_id is a plain Postgres sequence, never reset.
# start 6000 is ABOVE the live 2.x high-water mark (seq_eg_pgr_id = 5216 on
# 2026-09-20) so a v3-minted id can never duplicate a 2.x-minted one.
set -eu
curl -s -m 15 -X POST \
  -H 'X-Tenant-ID: pg' \
  -H 'X-User-ID: hybrid-shim-task3' \
  -H 'Content-Type: application/json' \
  -d '{"templateCode":"pgr.servicerequestid","config":{"template":"PG-PGR-{DATE:yyyy-MM-dd}-{SEQ}","sequence":{"scope":"GLOBAL","start":6000,"padding":{"length":6,"char":"0"}}}}' \
  -w '\n[%{http_code}]\n' \
  http://localhost:8000/idgen/v3/template
