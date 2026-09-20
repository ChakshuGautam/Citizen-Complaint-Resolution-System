#!/usr/bin/env node
// record-workflow-gaps.js
//
// The gaps that exist because of how the PGR workflow had to be REGISTERED in
// DIGIT 3.0, not because of any single request. Run once per fresh v3 workflow
// store, after register-pgr-workflow.sh:
//
//   node /opt/hybrid-shim/record-workflow-gaps.js
//
// Idempotent: a `field` already present in gaps.jsonl is skipped.
//
// This inventory is the point of Task 5. Every entry names a CCRS concept and
// says what would have to be built in DIGIT 3.0 to carry it. Each was verified
// against the LIVE v3 workflow service with a throwaway process definition, or
// against the LIVE 2.x BusinessService - nothing below is inferred from the
// OpenAPI spec alone, and where the spec and the deployed service disagree the
// entry says so.

const fs = require('node:fs');
const gaplog = require('./src/gaplog');

const DEP = 'egov-workflow-v2';

const ENTRIES = [
  // ---------------------------------------------------------------- actions
  {
    field: 'BusinessService.states[PENDINGATLME].actions[ESCALATE]',
    action: 'unmapped',
    detail:
      'CCRS ESCALATE cannot work in DIGIT 3.0, for TWO independent reasons. (1) It is a SELF-LOOP: ' +
      'the live 2.x BusinessService has ESCALATE at PENDINGATLME with nextState = PENDINGATLME ' +
      '(roles GRO, PGR_LME, PGR_VIEWER, SYSTEM). v3 STORES a self-loop action without complaint ' +
      'and then refuses it at runtime with 409 "invalid state transition: no transition" ' +
      '(verified live with a throwaway definition whose SELF action pointed at its own state). ' +
      'CCRS deliberately models escalation as a self-loop - docs/migration/' +
      'pgr-escalation-self-loop.md treats the older PENDINGATSUPERVISOR target as stale ' +
      'configuration to be migrated AWAY from - so this is not an artefact of one deployment. ' +
      '(2) Even if the loop worked, ESCALATE in CCRS RE-TARGETS the complaint: ' +
      'EscalationService/EscalationScheduler resolve the next supervisor and set the new assignee. ' +
      "v3's escalation (POST /workflow/v3/process/{processCode}/escalation, POST /workflow/v3/" +
      'auto/{processCode}/_escalate) only fires a named action on SLA breach; it has no concept of ' +
      'choosing WHO the work goes to. TO BUILD: (a) v3 must permit an action whose nextState ' +
      'equals its currentState, recording a transition row without a state change; (b) ' +
      'EscalationConfig needs an assignee-resolution strategy (a hook, or a rule referencing an ' +
      'org hierarchy) instead of a bare escalationAction.',
  },
  {
    field: 'BusinessService.states[*].actions[COMMENT]',
    action: 'unmapped',
    detail:
      'CCRS COMMENT is a SELF-LOOP at every non-terminal state - PENDINGFORASSIGNMENT, ' +
      'PENDINGFORREASSIGNMENT, PENDINGATLME, REJECTED and RESOLVED all carry ' +
      'COMMENT -> themselves for role CITIZEN in the live 2.x BusinessService. It is how a citizen ' +
      'adds to a complaint without moving it. v3 accepts the definition and then answers every ' +
      'COMMENT transition 409 "invalid state transition: no transition" (verified live). So ' +
      'citizen commenting is entirely unavailable through a v3 workflow. Note that v3 DOES carry a ' +
      '`comment` string on each transition, so comments attached to a REAL state change survive - ' +
      'it is the standalone comment that has nowhere to live. TO BUILD: v3 needs either self-loop ' +
      'transitions, or a first-class "annotate without transitioning" call on the process ' +
      'instance. There is no such endpoint in digit-specs/v3.0.0/workflow.yaml.',
  },
  {
    field: 'BusinessService.states[*].actions[SLA_ESCALATE]',
    action: 'unmapped',
    detail:
      'NO SLA_ESCALATE ACTION EXISTS IN THIS CCRS. The task brief names it as an expected gap; a ' +
      'grep of the whole Citizen-Complaint-Resolution-System tree finds the string nowhere, and ' +
      'the live PGR BusinessService has no such action. What CCRS actually has is ' +
      'EscalationScheduler, an in-process CCRS cron that finds SLA-breached complaints and issues ' +
      'an ordinary ESCALATE transition (EscalationScheduler:174) with an assignee it resolved ' +
      'itself. The corresponding v3 facility is POST /workflow/v3/auto/{processCode}/_escalate ' +
      'plus an EscalationConfig per state (stateSlaMinutes / processSlaMinutes -> ' +
      'escalationAction), which is genuinely closer to the concept than 2.x workflow-v2 offers. ' +
      'It is recorded as unmapped and NOT configured because the action it would have to fire is ' +
      'ESCALATE, which v3 cannot execute at all (see the ESCALATE entry): wiring an escalation ' +
      'config to a permanently-409 action would be theatre. TO BUILD: fix self-loops first, then ' +
      "EscalationConfig covers the timing half of CCRS's escalation and only the assignee-" +
      'resolution half remains.',
  },
  // ---------------------------------------------------------------- states
  {
    field: 'BusinessService.states[PENDINGATSUPERVISOR]',
    action: 'unmapped',
    detail:
      'NO PENDINGATSUPERVISOR STATE EXISTS IN THIS CCRS. The task brief names it as an expected ' +
      'gap; the live PGR BusinessService on tenant pg has nine states and this is not one of them ' +
      '(START, PENDINGFORASSIGNMENT, PENDINGFORREASSIGNMENT, PENDINGATLME, REJECTED, RESOLVED, ' +
      'CLOSEDAFTERREJECTION, CLOSEDAFTERRESOLUTION, CANCELLED). CCRS removed it on purpose: ' +
      'docs/migration/pgr-escalation-self-loop.md is a runbook for moving complaints OUT of ' +
      'PENDINGATSUPERVISOR and confirming it is absent from the active BusinessService, ' +
      'tests/integration-tests asserts its absence, and docs/escalation.md states the canonical ' +
      'workflow has no such state. Recorded so the inventory says WHY it is missing rather than ' +
      'leaving a named concept unaccounted for. The escalation semantics it used to carry now live ' +
      'in the ESCALATE self-loop, which is the entry above. Nothing to build in v3 for this one.',
  },
  {
    field: 'BusinessService.states[CANCELLED]',
    action: 'dropped',
    detail:
      'CANCELLED is in the live 2.x BusinessService (isTerminateState=true) and is UNREACHABLE - ' +
      'no action in any state has nextState = CANCELLED. It is registered in v3 anyway so the ' +
      'state list matches, but two things are lost. (1) Its terminal-ness: v3 ACCEPTS ' +
      'type TERMINAL_FAILURE and then SILENTLY REWRITES it to INTERMEDIATE (verified live - a ' +
      'probe state sent as TERMINAL_SUCCESS came back INTERMEDIATE), so v3 as deployed cannot ' +
      'distinguish a terminal state from a working one at all. The shim reports ' +
      'isTerminateState from additionalDetails it wrote itself, which is an annotation, not a ' +
      'service fact. (2) 2.x reaches CANCELLED only by direct state manipulation outside the ' +
      'action model; v3 has no such back door - POST /workflow/v3/transition is the only way to ' +
      'move an instance, and it requires a declared action. TO BUILD: v3 must persist the ' +
      'TERMINAL_SUCCESS / TERMINAL_FAILURE types its own ProcessDefinitionInput schema declares.',
  },
  // ---------------------------------------------------------------- org chain
  {
    field: 'escalation.reportingTo',
    action: 'unmapped',
    detail:
      'ESCALATION UP THE HRMS reportingTo CHAIN HAS NO v3 EQUIVALENT. CCRS resolves the next ' +
      'escalation target from HRMS: HRMSUtil reads ' +
      '$.Employees[0].assignments[?(@.isCurrentAssignment==true)].reportingTo, ' +
      'HrmsProjectionRepository keeps a local projection of the whole reportingTo tree, and ' +
      'EscalationService walks it one rung per ESCALATE, tracking escalationLevel / ' +
      'lastEscalatedAt / escalatedFrom / escalatedTo in the complaint\'s additionalDetail and ' +
      'resetting the SLA clock at each level. DIGIT 3.0 workflow models NONE of this: a v3 ' +
      'ProcessInstance holds `assignees` as opaque user-id STRINGS with no organisational ' +
      'relationship between them, EscalationConfig can only name a fixed escalationAction, and ' +
      'there is no reporting hierarchy anywhere in digit-specs/v3.0.0 - employee.yaml has no ' +
      'reportingTo, and the v3 stack deployed here runs no HRMS service at all. TO BUILD, in ' +
      'order: (1) an org-hierarchy service in v3 (or a reportingTo edge on the employee/individual ' +
      'registry) that can answer "who is above this user"; (2) an assignee-resolution strategy on ' +
      'EscalationConfig that can call it; (3) per-instance escalation state (level, last escalated ' +
      'at, from, to) with a per-level SLA reset, since v3 stateSla resets only on a real state ' +
      'change and ESCALATE is a self-loop. Until all three exist, pointing CCRS at a v3 workflow ' +
      'silently disables auto-escalation: EscalationScheduler will keep firing ESCALATE ' +
      'transitions and v3 will keep answering 409.',
  },
  // ---------------------------------------------------------------- shape of the definition
  {
    field: 'BusinessService.states[START]',
    action: 'faked',
    detail:
      'The 2.x PGR start state is NAMELESS: uuid eaf4ebb3-9a8e-4671-a82e-04c2fe73ac83 has ' +
      '"state": null, "applicationStatus": null, isStartState=true, isStateUpdatable=true. v3 ' +
      'requires every state to carry a non-empty `code` (minLength 1), so the registered ' +
      'definition invents the code START. The translator maps it back to null when it rebuilds a ' +
      '2.x BusinessService, so CCRS sees what it expects - but a v3-native client reading the ' +
      'definition sees a state CCRS does not believe exists, and any v3 process instance that ' +
      'stops at START (which happens when the very first transition is rejected - a 403 on APPLY ' +
      'still leaves an instance parked at the initial state, verified live) is in a state no 2.x ' +
      'status code corresponds to. TO BUILD: v3 would have to allow an unnamed initial state, or ' +
      "2.x's nameless start state would have to be given a code in CCRS's own configuration.",
  },
  {
    field: 'BusinessService.states[*].isTerminateState',
    action: 'dropped',
    detail:
      'STATE TYPE IS SILENTLY DISCARDED BY THE LIVE v3 SERVICE. ProcessDefinitionInput.StateInput ' +
      'declares type INITIAL | INTERMEDIATE | DECISION | TERMINAL_SUCCESS | TERMINAL_FAILURE, and ' +
      'the registered PGR definition sends TERMINAL_SUCCESS for RESOLVED / CLOSEDAFTERRESOLUTION ' +
      'and TERMINAL_FAILURE for REJECTED / CLOSEDAFTERREJECTION / CANCELLED, matching 2.x\'s ' +
      'isTerminateState=true. Reading the definition back shows every one of them as ' +
      'INTERMEDIATE. Only INITIAL survives. Verified with a throwaway definition, both with and ' +
      'without outbound actions on the terminal state, so it is not a validation side effect. ' +
      'Consequence: nothing in v3 can tell a caller that a complaint is closed - CCRS gets that ' +
      'from applicationStatus instead, so the lifecycle is unaffected, but a v3-native consumer ' +
      'of this process definition cannot compute completion. TO BUILD: persist and return the ' +
      "state type the service's own schema accepts.",
  },
  {
    field: 'BusinessService.states[*].actions[*].roles',
    action: 'faked',
    detail:
      'ROLE-GUARDED ACTIONS SURVIVE, BUT ONLY OFF-SPEC AND ONLY OVER AN UNSIGNED JWT. ' +
      'digit-specs/v3.0.0/workflow.yaml ActionInput declares exactly code, label, nextState, ' +
      'assigneeCheck and additionalDetails - there is NO roles field. The deployed service ' +
      'nevertheless accepts `roles`, stores it, returns it, and ENFORCES it: a transition whose ' +
      'action declares roles is answered 403 "caller role does not permit the requested action: ' +
      'guard check failed" unless the caller presents matching roles. The only channel it reads ' +
      'them from is Authorization: Bearer <jwt> with realm_access.roles; the X-Roles header named ' +
      "in the service's own Headers constant is ignored by this build (verified: same request, " +
      'X-Roles -> 403, Bearer -> 200), and the signature is not checked, so {"alg":"none"} is ' +
      'honoured. All eleven CCRS role-guarded actions are therefore registered WITH their 2.x ' +
      'roles and the shim forges an unsigned bearer per request. The alternative - registering ' +
      'actions with no roles - was rejected: a roleless action is open to everyone (verified), ' +
      "which would delete CCRS's entire authorisation model without a single error. TO BUILD: " +
      '(1) declare roles in ActionInput; (2) offer a non-JWT role channel, or make every caller ' +
      'obtain a real Keycloak token; (3) verify the signature, because today any client can claim ' +
      'any role. Note also that 2.x role names are flat strings scoped by tenant, while v3 realm ' +
      'roles come from Keycloak - nothing reconciles the two namespaces.',
  },
  {
    field: 'BusinessService.states[*].actions[*].active',
    action: 'dropped',
    detail:
      '2.x soft-deletes a workflow action with active=false, so a decommissioned action stays ' +
      'visible in the definition and in history. v3 has no such flag anywhere in ' +
      'ProcessDefinitionInput / ActionInput; DELETE /workflow/v3/process/{processCode}/state/' +
      '{stateCode}/action/{actionCode} removes it outright. Every action in the live PGR ' +
      'BusinessService is active=true, so nothing is lost today, and the translator returns null ' +
      'rather than asserting true. TO BUILD: an `active` (or validFrom/validTo) flag on ' +
      'ActionInput, without which a v3 process definition cannot be audited for what it used to ' +
      'allow.',
  },
  {
    field: 'BusinessService.states[*].docUploadRequired',
    action: 'dropped',
    detail:
      '2.x State carries docUploadRequired (false on every PGR state) and isStateUpdatable ' +
      '(true only on the start state). Neither has any v3 field: StateInput has code, name, type, ' +
      'description, sla, additionalDetails and actions, and nothing else. Both are stashed in ' +
      "the registered definition's state additionalDetails as twoXDocUploadRequired / " +
      'twoXIsStateUpdatable and read back from there by the translator, which means they are ' +
      'CCRS configuration parked inside v3, not anything v3 understands or enforces. TO BUILD: ' +
      'v3 needs per-state document requirements and an updatable flag, or the modules on top must ' +
      'own them.',
  },
  {
    field: 'ProcessDefinition.additionalDetails',
    action: 'dropped',
    detail:
      'THERE IS NOWHERE TO PARK 2.x-ONLY CONFIGURATION. ProcessDefinitionInput and StateInput both ' +
      'declare `additionalDetails` as "free-form metadata, additionalProperties: true", and the ' +
      'registration payload uses them for everything v3 has no field for: the owning module ' +
      '(business = pgr-services), the source BusinessService uuid, and per-state ' +
      'twoXIsTerminateState / twoXIsStateUpdatable / twoXDocUploadRequired. Reading the ' +
      'registered PGR definition back shows additionalDetails ABSENT at BOTH levels - the ' +
      'deployed service accepts the field and silently discards it (verified on the real ' +
      'registration, not a probe). So every 2.x field listed in the neighbouring entries comes ' +
      'back null in practice, not merely renamed, and the escape hatch the spec advertises does ' +
      'not exist. The YAML keeps the annotations because they document intent and would work the ' +
      'moment the service honours the field. TO BUILD: persist and return additionalDetails on ' +
      'process definitions and states, as ProcessDefinitionInput already promises. Until then a ' +
      "shim cannot round-trip ANY 2.x concept v3 lacks - it can only report its absence.",
  },
  {
    field: 'BusinessService.getUri',
    action: 'dropped',
    detail:
      '2.x BusinessService declares getUri / postUri (the callback endpoints workflow-v2 uses to ' +
      'fetch or notify the owning module) and `business` (the owning module, "pgr-services"). v3 ' +
      'has none of the three: a v3 process definition is code, name, description, version, sla, ' +
      'slotPercentage, additionalDetails and states. `business` is parked in the definition\'s ' +
      'additionalDetails so the translator can return it; getUri and postUri are dropped entirely ' +
      'and come back null. Both are null on the live PGR BusinessService too, so nothing breaks ' +
      'today. TO BUILD: nothing, if v3 never calls back into modules - but that is a real ' +
      'architectural difference, not an omission, and it should be an explicit decision.',
  },
  {
    field: 'ProcessInstance.nextActions',
    action: 'dropped',
    detail:
      '2.x /process/_search returns nextActions[] on every instance: the actions available from ' +
      "the current state, already filtered by the caller's roles (verified live - a GRO searching " +
      'a RESOLVED complaint gets REOPEN but not the CITIZEN-only COMMENT). The DIGIT UI drives its ' +
      'action buttons off it. The v3 GET /workflow/v3/transition rows return no such field, and ' +
      'neither does the POST reply; digit-specs declares a TransitionNextActionDetail schema but ' +
      'the deployed service never populates one on these endpoints. The shim cannot compute it ' +
      'either: that would take a second v3 call (GET /process/definition/{processCode}) per ' +
      'instance, which the one-request-in / one-request-out translator contract has no way to ' +
      'make. Emitted as null. CCRS itself does not read nextActions, so the complaint lifecycle ' +
      'is unaffected; the employee UI would lose its buttons. TO BUILD: v3 should return the ' +
      'role-filtered available actions alongside each process instance.',
  },
  {
    field: 'ProcessInstance.rating',
    action: 'dropped',
    detail:
      '2.x /process/_search returns rating (0 until the citizen RATEs) and previousStatus and ' +
      'entity on every instance. v3 has no field for any of them - a v3 ProcessInstance is id, ' +
      'processCode, version, entityId, action, comment, documents, assigner, assignees, ' +
      'currentState, stateSla, processSla, isLatest, escalated, additionalDetails, auditDetail and ' +
      'optionally transitionHistory. All three come back null. rating is the one that matters: ' +
      "CCRS's RATE action carries the citizen's satisfaction score, and on v3 it would have to " +
      'live in the transition\'s additionalDetails - which v3 does accept, so this is carryable ' +
      'with a small CCRS change rather than a v3 change.',
  },
  {
    field: 'ProcessInstance.search.multipleBusinessIds',
    action: 'unmapped',
    detail:
      'THE EMPLOYEE COMPLAINT LIST CANNOT BE SERVED BY v3. WorkflowService.enrichWorkflow joins ' +
      'ALL the complaints in one search result to their workflow in ONE call - ' +
      '/process/_search?businessIds=<comma-separated list> - and throws WORKFLOW_NOT_FOUND unless ' +
      'the reply holds exactly one ProcessInstance per id (WorkflowService:142). v3 GET ' +
      '/workflow/v3/transition takes a SINGLE entityId: the comma form returns ' +
      '{"totalCount":0,"processInstances":[]} and the repeated-parameter form ' +
      '?entityId=a&entityId=b honours only the first (both verified live). So any employee or ' +
      'citizen search returning MORE THAN ONE complaint fails with WORKFLOW_NOT_FOUND. This is ' +
      'loud, and deliberately so - the shim forwards the comma-separated string verbatim rather ' +
      'than answering a partial list, because a half-filled inbox with HTTP 200 is exactly the ' +
      'failure the Task 4 boundary switchover produced. The baseline lifecycle does not catch it: ' +
      'both of its searches are scoped to one serviceRequestId. TO BUILD: v3 needs a multi-valued ' +
      'entityId filter on GET /transition (its own OpenAPI already models comma-separated arrays ' +
      'elsewhere), or a bulk transition-search endpoint.',
  },
  {
    field: 'ProcessInstance.search.processCodeRequired',
    action: 'unmapped',
    detail:
      "2.x business ids are globally unique, so /process/_search by businessIds carries no " +
      'business service at all. v3 refuses that search: 400 "processCode query parameter is ' +
      'required when filtering by entityId". Nothing in the 2.x request can supply it, so the ' +
      'shim substitutes WORKFLOW_PROCESS_CODE (default PGR) and gap-logs every use. It is correct ' +
      'here only because pgr-services is the sole caller and PGR its sole business service; a ' +
      'second module behind this shim would have its instances searched under the wrong process ' +
      'and be answered an empty list with HTTP 200. TO BUILD: v3 should allow a search keyed on ' +
      'entityId alone, since entity ids are unique across processes in practice.',
  },
  {
    field: 'ProcessInstance.assignes',
    action: 'dropped',
    detail:
      'v3 stores assignees as bare user-id STRINGS. 2.x returns the whole User on every ' +
      'ProcessInstance - id, userName, name, type, mobileNumber, emailId, the full roles[] array ' +
      'and tenantId (verified live: id=40, userName=PGGRO1, "PG Grievance Officer", four roles). ' +
      'The same is true of `assigner`, which v3 additionally BLANKS: a transition sent with ' +
      'assigner="21fc9b81-..." is echoed back with assigner="" and every later search agrees. The ' +
      'shim emits {uuid} objects and falls back to the acting user from the inbound RequestInfo ' +
      'for assigner. CCRS survives - WorkflowService.getWorkflow reads only User::getUuid and ' +
      'NotificationService re-fetches the user - but every field-by-field comparison of a ' +
      'transition reply differs here, and any consumer reading assignes[0].name off the workflow ' +
      'reply gets null. TO BUILD: either v3 joins the user registry into its replies, or every ' +
      'consumer accepts an id and resolves it itself (which is the cleaner v3-native answer, and ' +
      'should be stated as a decision rather than left as a surprise).',
  },
  {
    field: 'ProcessInstance.transition.batch',
    action: 'unmapped',
    detail:
      '2.x POST /process/_transition takes a LIST of ProcessInstances and transitions them ' +
      'together. v3 POST /workflow/v3/transition takes exactly one entity per call. The shim ' +
      'throws on a batch rather than transitioning the first and discarding the rest. ' +
      'pgr-services always sends exactly one (Collections.singletonList in ' +
      'WorkflowService.updateWorkflowStatus), so CCRS is unaffected, but any 2.x module that ' +
      'batches transitions cannot be served, and a shim cannot fix it without giving up the ' +
      'one-request-in / one-request-out contract or losing atomicity. TO BUILD: a bulk transition ' +
      'endpoint in v3.',
  },
  {
    field: 'ProcessInstance.transition.strandedInitialInstance',
    action: 'faked',
    detail:
      'A REJECTED FIRST TRANSITION STILL CREATES A PROCESS INSTANCE IN v3. POST ' +
      '/workflow/v3/transition creates the instance at the INITIAL state and then applies the ' +
      'action; when the action is refused (403 on a role guard), the 403 is returned but the ' +
      'instance REMAINS, parked at INITIAL (verified live - the next call reported ' +
      '"action is not valid for current state <initial-state-uuid>"). 2.x creates nothing on a ' +
      'failed transition. So a CCRS complaint whose APPLY is rejected leaves a v3 process ' +
      'instance with no CCRS row behind it, in a state (START) that maps to no 2.x ' +
      'applicationStatus, and a later retry of the same entityId resumes from START rather than ' +
      'starting clean. Recorded as faked because the shim reports the 403 faithfully and says ' +
      'nothing about the orphan it just caused. TO BUILD: v3 should roll the instance back when ' +
      'the creating transition fails.',
  },
  {
    field: 'lifecycle.service.processInstance',
    action: 'dropped',
    detail:
      'MEASURED, NOT PREDICTED. With EGOV_WORKFLOW_HOST on the shim the full CCRS lifecycle ' +
      'passes 7/7 (create -> assign -> resolve -> search, PENDINGFORASSIGNMENT -> PENDINGATLME -> ' +
      'RESOLVED), but compare.sh against the 2.x baseline reports 55 changed values, 193 fields ' +
      'missing and 11 new, out of 731 compared facts - and EVERY ONE of them is inside ' +
      'ServiceWrappers[0].service.processInstance on the three steps that transition ' +
      '(create/assign/resolve). The two SEARCH steps are byte-identical to the 2.x baseline, so ' +
      'the process-search translation is exact for everything CCRS reads. The divergence is one ' +
      'fact, repeated: 2.x staples the whole State DEFINITION and the whole assignee/assigner ' +
      'USER onto every transition reply, and v3 returns a state CODE and a user-id STRING. The ' +
      '193 missing fields are state.actions[] (8 actions x uuid/currentState/nextState/roles[]/ ' +
      'auditDetails) and the roles[] arrays of assignes/assigner; the 55 changed are ' +
      'state.uuid/sla/businessServiceId/docUploadRequired/isStartState/isStateUpdatable/ ' +
      'isTerminateState, the User attributes id/userName/name/mobileNumber/type/tenantId, ' +
      'documents ([] -> null) and moduleName ("pgr-services" -> "PGR", because 2.x overwrites the ' +
      "caller's moduleName with BusinessService.business and v3 has neither field). The 11 new " +
      'are null placeholders Jackson emits for the same absences. compare.sh\'s ignore list was ' +
      'NOT touched: none of this is volatility, all of it is v3 behaving differently. TO BUILD: ' +
      'v3 would have to join the process definition and the user registry into its transition ' +
      'replies - or every 2.x consumer must be rewritten to resolve codes and ids itself, which ' +
      'is the v3-native answer and should be an explicit decision.',
  },
  {
    field: 'ProcessInstance.search.sizeCap',
    action: 'defaulted',
    detail:
      'v3 GET /workflow/v3/transition pages, and refuses size>100 with 400 "size must be an ' +
      'integer between 1 and 100". 2.x /process/_search does not page at all and returns the ' +
      'whole history. The shim asks for size=100 on every search. A complaint with more than 100 ' +
      'transitions - reachable through repeated REOPEN/COMMENT/ESCALATE cycles - is truncated by ' +
      "v3 with no error, and NotificationService.getEmployeeName, which scans the history for the " +
      'last ASSIGN, would then resolve the wrong assignee or none. The translator contract is one ' +
      'request in, one request out, so it cannot page. TO BUILD: nothing in v3 - the cap is ' +
      'reasonable - but a 2.x caller that assumes unbounded results must be changed, not shimmed.',
  },
];

function main() {
  const file = gaplog.logPath();
  let existing = new Set();
  if (fs.existsSync(file)) {
    for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try {
        existing.add(JSON.parse(line).field);
      } catch {
        /* a malformed line is not a reason to re-record everything */
      }
    }
  }

  let wrote = 0;
  for (const e of ENTRIES) {
    if (existing.has(e.field)) continue;
    gaplog.record({ dep: DEP, ...e });
    wrote += 1;
  }
  process.stdout.write(
    `record-workflow-gaps: ${wrote} new, ${ENTRIES.length - wrote} already present -> ${file}\n`
  );
}

if (require.main === module) main();
module.exports = { ENTRIES };
