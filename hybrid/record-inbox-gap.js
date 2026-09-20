// record-inbox-gap.js
//
// One gap-log entry for the measuring-instrument finding that
// baseline/inbox-check.sh produced. The workflow translator already gap-logs
// the multi-id search every time it happens ("businessIds / unmapped"); this
// entry records the END-TO-END consequence, measured on both engines, so the
// gap log carries the A/B evidence and not just the translator's own note.
//
// Run: docker cp /opt/hybrid-shim/record-inbox-gap.js hybrid-shim:/app/ && docker exec hybrid-shim node /app/record-inbox-gap.js
const gaplog = require('/app/src/gaplog');

gaplog.record({
  dep: 'egov-workflow-v2',
  field: 'employee inbox (>=2 complaints)',
  action: 'unmapped',
  detail:
    'MEASURED A/B with baseline/inbox-check.sh (2026-09-20, tenant pg). The script creates TWO complaints, ' +
    'authenticates as employee PGGRO1 and runs the inbox-shaped employee search ' +
    'POST /pgr-services/v2/request/_search?tenantId=pg&ids=<uuidA>,<uuidB>. ' +
    'RESULT ON 2.x (EGOV_WORKFLOW_HOST=http://egov-workflow-proxy:8109/): HTTP 200, 2 ServiceWrappers, ' +
    'both complaints enriched - run /opt/hybrid-shim/runs/inbox-2x.json. ' +
    'RESULT ON v3 (EGOV_WORKFLOW_HOST=http://hybrid-shim:9100/): HTTP 400, 0 ServiceWrappers, ' +
    'Errors[0] = {"code":"WORKFLOW_NOT_FOUND","message":"The workflow object is not found"} - run ' +
    '/opt/hybrid-shim/runs/inbox-v3.json. Same script, same box, same minute; the only variable is the ' +
    'workflow host. CAUSE: WorkflowService.enrichWorkflow (service/WorkflowService.java:131-145) joins every ' +
    'complaint in a search result to its workflow in ONE call, ?businessIds=<csv>, and throws unless ' +
    'processInstances.size() == serviceRequestIds.size(). DIGIT 3.0 GET /workflow/v3/transition serves exactly ' +
    'ONE entityId: the comma form yields totalCount 0 and the repeated-parameter form honours only the first ' +
    'value. So EVERY employee inbox holding two or more complaints is dead on v3, while lifecycle.sh stays ' +
    'green because both of its searches are scoped to a single serviceRequestId. ' +
    'WHAT v3 WOULD HAVE TO GROW: a multi-valued entityId filter on GET /workflow/v3/transition (or a bulk ' +
    'transition search), so one call can answer for N entities. A shim cannot paper over it: the ' +
    'one-request-in / one-request-out translator contract cannot fan out to N v3 calls, and answering a ' +
    'PARTIAL list would silently drop complaints out of an inbox instead of failing loudly. ' +
    'INSTRUMENT CAVEAT, recorded because it invalidated the first version of this script: a tenant-wide query ' +
    '(?applicationStatus=PENDINGFORASSIGNMENT) fails with the SAME WORKFLOW_NOT_FOUND on BOTH engines, because ' +
    'the tenant now holds complaints whose process instances live in the other engine\'s store - workflow state ' +
    'was never migrated between 2.x and v3. That is its own migration requirement: flipping ' +
    'EGOV_WORKFLOW_HOST is not reversible for complaints already in flight.',
});
