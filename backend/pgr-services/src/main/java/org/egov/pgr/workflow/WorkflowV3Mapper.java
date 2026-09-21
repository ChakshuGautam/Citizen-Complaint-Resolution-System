package org.egov.pgr.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.egov.common.contract.request.User;
import org.egov.pgr.web.models.AuditDetails;
import org.egov.pgr.web.models.Document;
import org.egov.pgr.web.models.workflow.Action;
import org.egov.pgr.web.models.workflow.BusinessService;
import org.egov.pgr.web.models.workflow.ProcessInstance;
import org.egov.pgr.web.models.workflow.State;
import org.egov.tracer.model.CustomException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a DIGIT 3.0 workflow reply into the 2.x workflow models the rest of pgr-services reads.
 *
 * <p>This is the single place the v3 renames live. There are a lot of them, none of which
 * CCRS's case-insensitive ObjectMapper can absorb — case insensitivity fixes
 * {@code processInstances} → {@code ProcessInstances} and nothing else:
 *
 * <table>
 *   <tr><th>v3</th><th>2.x</th></tr>
 *   <tr><td>{@code definitions}</td><td>{@code BusinessServices}</td></tr>
 *   <tr><td>definition {@code id} / {@code code} / {@code sla}</td>
 *       <td>{@code uuid} / {@code businessService} / {@code businessServiceSla}</td></tr>
 *   <tr><td>state {@code id} / {@code code}</td><td>{@code uuid} / {@code state} + {@code applicationStatus}</td></tr>
 *   <tr><td>action {@code id} / {@code code}</td><td>{@code uuid} / {@code action}</td></tr>
 *   <tr><td>{@code entityId}</td><td>{@code businessId}</td></tr>
 *   <tr><td>{@code processCode}</td><td>{@code businessService}</td></tr>
 *   <tr><td>{@code currentState} (a bare code STRING)</td><td>{@code state} (the whole State object)</td></tr>
 *   <tr><td>{@code assignees} (uuid STRINGS)</td><td>{@code assignes} (User objects)</td></tr>
 *   <tr><td>{@code auditDetail.modifiedBy/modifiedTime}</td><td>{@code auditDetails.lastModifiedBy/lastModifiedTime}</td></tr>
 *   <tr><td>{@code processSla}</td><td>{@code businesssServiceSla} (three s's, as upstream spells it)</td></tr>
 * </table>
 *
 * <p>Shapes are those of the running v3 stack, probed on 2026-09-21 rather than read off
 * {@code digit-specs/v3.0.0/workflow.yaml}, which disagrees with the shipped build. A transition
 * answers ONE object; a search answers
 * {@code {"totalCount":n,"page":p,"size":s,"processInstances":[...]}}; a definition search answers
 * {@code {"definitions":[...],"page":0,"size":n,"totalCount":n}}.
 *
 * <p><b>The one rule that is not a rename.</b> {@code state.applicationStatus} is the single field
 * the 2.x workflow contract cannot do without — {@code WorkflowService.updateWorkflowStatus} writes
 * it onto every complaint. v3 reports it as {@code currentState}. The alias chain below tries
 * {@code currentState}, then {@code state.code}, then a bare {@code state}; if all three miss, this
 * class <b>throws</b> rather than returning a State full of nulls. The hybrid shim returned null
 * there and recorded the fact in a gap log, which is the correct choice for a translator that must
 * answer something; CCRS is not a translator and has somewhere better to put the failure. A null
 * applicationStatus written onto a complaint at HTTP 200 with no log line is the quietest failure
 * this system can have.
 */
@Slf4j
public class WorkflowV3Mapper {

    /**
     * The 2.x PGR start state has no name. v3 forbids an empty state code, so the registered v3
     * definition calls it START; reading it back turns it into null again, because
     * {@code MigrationUtils.getStatusToUUIDMap} keys a map on the 2.x spelling.
     */
    public static final String V3_START_STATE = "START";

    public static final String PROCESS_INSTANCES_KEY = "processInstances";
    public static final String DEFINITIONS_KEY = "definitions";
    public static final String TOTAL_COUNT_KEY = "totalCount";

    private final ObjectMapper mapper;

    public WorkflowV3Mapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ envelope

    /**
     * The rows under {@code key}, or an empty list when the key is present and empty.
     *
     * <p>An <b>absent</b> key is not an empty result, it is an unreadable reply, and it throws.
     * That distinction is the whole lesson of the boundary-relationship failure this migration
     * already hit once: a v3 envelope whose key CCRS did not know bound to a well-formed model with
     * no rows, was indistinguishable from a legitimate empty answer, and was then cached as a
     * success for thirty minutes.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rowsOf(Map<?, ?> body, String key, String which) {
        if (body == null)
            throw new CustomException("WORKFLOW_RESPONSE_UNREADABLE",
                    "v3 " + which + " returned no body at all, where a " + key + " list was expected");

        Object rows = valueOf(body, key);
        if (rows == null)
            throw new CustomException("WORKFLOW_RESPONSE_UNREADABLE",
                    "v3 " + which + " answered 2xx but carried no \"" + key + "\" key; keys present: "
                            + body.keySet() + ". Refusing to read that as an empty result: an envelope whose key "
                            + "CCRS does not know binds to a model full of nulls and throws nothing, which is how a "
                            + "wrong wire shape reaches a citizen as HTTP 200 and no complaints.");
        if (!(rows instanceof List))
            throw new CustomException("WORKFLOW_RESPONSE_UNREADABLE",
                    "v3 " + which + " answered a \"" + key + "\" that is not a list but a "
                            + rows.getClass().getName());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : (List<?>) rows)
            if (row instanceof Map)
                out.add((Map<String, Object>) row);
        return out;
    }

    /** v3's own count of the matching rows, independent of how many this page carried. */
    public Long totalCountOf(Map<?, ?> body) {
        Object total = body == null ? null : valueOf(body, TOTAL_COUNT_KEY);
        return total instanceof Number ? ((Number) total).longValue() : null;
    }

    /**
     * Refuses a silently truncated result.
     *
     * <p>v3 pages every list endpoint, defaults {@code size} to 20 and caps it at 100 (400 above
     * it), while the 2.x envelope CCRS reads has no paging field at all — so a short list is
     * indistinguishable from a complete one at HTTP 200. The only witness is v3's own
     * {@code totalCount}, which the shim compares to the rows that arrived and gap-logs. CCRS
     * pages instead (see {@code WorkflowService}), and this is the assertion that the paging
     * actually finished: if it did not, the caller must hear about it rather than act on a
     * plausible short list.
     */
    public void assertNotTruncated(Long totalCount, int rowsCollected, String which) {
        if (totalCount != null && totalCount > rowsCollected)
            throw new CustomException("WORKFLOW_RESULT_TRUNCATED",
                    "v3 " + which + " reported totalCount=" + totalCount + " but only " + rowsCollected
                            + " row(s) were collected. v3 truncates a list at its page size with no error and the "
                            + "2.x envelope has no field to carry \"there was more\", so this is reported here "
                            + "rather than handed on as a complete-looking short result.");
    }

    // ------------------------------------------------------------------ process instances

    /**
     * One v3 transition row — from the POST response object or from a row of a GET search, whose
     * shapes agree on every field read here.
     *
     * @param fallbackProcessCode used only when v3 omits {@code processCode}; CCRS always knows it
     * @param moduleName          echoed from the 2.x request; v3 has no such field and 2.x itself
     *                            overwrote CCRS's "PGR" with the business service's "pgr-services",
     *                            so the baseline differs here and nothing in CCRS reads it back
     * @param assignerUuid        the acting user; v3 accepts {@code assigner} and echoes it as ""
     */
    public ProcessInstance processInstance(Map<?, ?> v3, String tenantId, String fallbackProcessCode,
                                           String moduleName, String assignerUuid, String which) {
        if (v3 == null)
            throw new CustomException("WORKFLOW_RESPONSE_UNREADABLE",
                    "v3 " + which + " carried a null process instance");

        String processCode = stringOf(v3, "processCode");
        ProcessInstance out = new ProcessInstance();
        out.setId(stringOf(v3, "id"));
        out.setTenantId(tenantId);
        out.setBusinessService(processCode == null ? fallbackProcessCode : processCode);
        out.setBusinessId(stringOf(v3, "entityId"));
        out.setAction(stringOf(v3, "action"));
        out.setModuleName(moduleName);
        // v3's stateSla is the 2.x State.sla; ProcessInstance itself has no such field, only
        // businesssServiceSla (three s's), which v3 calls processSla.
        out.setState(stateFromCode(currentStateOf(v3, which), tenantId, longOf(v3, "stateSla")));
        out.setComment(blankToNull(stringOf(v3, "comment")));
        out.setDocuments(documentsOf(v3));
        out.setAssignes(assigneesOf(v3));

        String assigner = blankToNull(stringOf(v3, "assigner"));
        out.setAssigner(userOf(assigner != null ? assigner : assignerUuid));

        Object audit = valueOf(v3, "auditDetail");
        out.setAuditDetails(auditDetails(audit == null ? valueOf(v3, "auditDetails") : audit));
        out.setBusinesssServiceSla(longOf(v3, "processSla"));
        return out;
    }

    /**
     * The 2.x State rebuilt from the one fact v3 reports about it: its code.
     *
     * <p>Everything else 2.x staples onto a transition reply — {@code uuid},
     * {@code businessServiceId}, {@code docUploadRequired}, {@code isStateUpdatable},
     * {@code isTerminateState} and the whole {@code actions[]} array with its own uuids, roles and
     * audit — is absent from the v3 transition endpoints and is left null rather than invented.
     * Recovering it needs a second call to {@code GET /workflow/v3/process/definition}. Nothing in
     * pgr-services reads any of it on this path; the DIGIT UI drives its action buttons off
     * {@code state.actions[]} and does.
     */
    public State stateFromCode(String code, String tenantId, Long sla) {
        return State.builder()
                .tenantId(tenantId)
                .sla(sla)
                .state(code)
                .applicationStatus(code)
                .isStartState(V3_START_STATE.equals(code) ? Boolean.TRUE : null)
                .build();
    }

    /**
     * The state code, tried under every spelling this build or a plausible later one might use.
     *
     * <p>Throws when all of them miss. See the class comment: this is the one field the contract
     * cannot do without, and a null here is written onto a complaint with no error and no log.
     */
    public String currentStateOf(Map<?, ?> v3, String which) {
        String direct = stringOf(v3, "currentState");
        if (direct != null && !direct.trim().isEmpty())
            return direct;

        Object nested = valueOf(v3, "state");
        if (nested instanceof Map) {
            String code = stringOf((Map<?, ?>) nested, "code");
            if (code != null && !code.trim().isEmpty())
                return code;
        }
        if (nested instanceof String && !((String) nested).trim().isEmpty())
            return (String) nested;

        throw new CustomException("WORKFLOW_STATE_MISSING",
                "v3 " + which + " returned a process row carrying none of the state spellings CCRS knows "
                        + "(\"currentState\", \"state.code\", \"state\"); keys present: " + v3.keySet()
                        + ". state.applicationStatus is the one field the 2.x workflow contract cannot do without "
                        + "— WorkflowService writes it onto the complaint — so this fails here rather than writing "
                        + "null onto every complaint at HTTP 200 with nothing in any log.");
    }

    // ------------------------------------------------------------------ process definitions

    /** One v3 process definition as the 2.x BusinessService the business-service search returns. */
    public BusinessService businessService(Map<?, ?> v3, String tenantId) {
        if (v3 == null)
            throw new CustomException("WORKFLOW_RESPONSE_UNREADABLE",
                    "v3 GET /workflow/v3/process/definition carried a null definition");

        String processId = stringOf(v3, "id");
        List<State> states = new ArrayList<>();
        Object rawStates = valueOf(v3, "states");
        if (rawStates instanceof List)
            for (Object state : (List<?>) rawStates)
                if (state instanceof Map)
                    states.add(definitionState((Map<?, ?>) state, processId, tenantId));

        return BusinessService.builder()
                .tenantId(tenantId)
                .uuid(processId)
                .businessService(stringOf(v3, "code"))
                // 2.x BusinessService.business names the owning module. v3 has no column for it and
                // discards the additionalDetails the spec advertises as the escape hatch, so it is
                // null — and nothing in pgr-services reads it back.
                .business(null)
                .getUri(null)
                .postUri(null)
                .businessServiceSla(longOf(v3, "sla"))
                .states(states.isEmpty() ? null : states)
                .auditDetails(auditDetails(valueOf(v3, "auditDetail")))
                .build();
    }

    /**
     * One state of a v3 process definition.
     *
     * <p>{@code isTerminateState} is permanently null: the v3 states table has no state-type
     * column, {@code TERMINAL_SUCCESS}/{@code TERMINAL_FAILURE} are accepted on write and silently
     * rewritten to {@code INTERMEDIATE}, and the additionalDetails a registration could park the
     * original flag in is discarded too. CCRS is unaffected — it takes completion from
     * {@code applicationStatus} — but do not build anything new on that field.
     */
    public State definitionState(Map<?, ?> v3, String processId, String tenantId) {
        String code = stringOf(v3, "code");
        boolean isStart = Boolean.TRUE.equals(booleanOf(v3, "isInitial")) || "INITIAL".equals(stringOf(v3, "type"));
        String twoXState = isStart && V3_START_STATE.equals(code) ? null : code;

        List<Action> actions = new ArrayList<>();
        Object rawActions = valueOf(v3, "actions");
        if (rawActions instanceof List)
            for (Object action : (List<?>) rawActions)
                if (action instanceof Map)
                    actions.add(definitionAction((Map<?, ?>) action, stringOf(v3, "id"), tenantId));

        return State.builder()
                .uuid(stringOf(v3, "id"))
                .tenantId(tenantId)
                .businessServiceId(processId)
                .sla(longOf(v3, "sla"))
                .state(twoXState)
                .applicationStatus(twoXState)
                .docUploadRequired(null)
                .isStartState(isStart ? Boolean.TRUE : Boolean.FALSE)
                .isTerminateState(null)
                .isStateUpdatable(null)
                .actions(actions.isEmpty() ? null : actions)
                .auditDetails(auditDetails(valueOf(v3, "auditDetail")))
                .build();
    }

    /** One action of a v3 definition state. {@code currentState}/{@code nextState} are state uuids, as in 2.x. */
    public Action definitionAction(Map<?, ?> v3, String stateId, String tenantId) {
        String currentState = stringOf(v3, "currentState");
        return Action.builder()
                .uuid(stringOf(v3, "id"))
                .tenantId(tenantId)
                .currentState(currentState == null ? stateId : currentState)
                .action(stringOf(v3, "code"))
                .nextState(stringOf(v3, "nextState"))
                .roles(stringsOf(v3, "roles"))
                .auditDetails(auditDetails(valueOf(v3, "auditDetail")))
                .build();
    }

    // ------------------------------------------------------------------ small pieces

    /** v3 {@code auditDetail{createdBy,createdTime,modifiedBy,modifiedTime}} → 2.x {@code auditDetails}. */
    public AuditDetails auditDetails(Object v3Audit) {
        if (!(v3Audit instanceof Map))
            return null;
        Map<?, ?> audit = (Map<?, ?>) v3Audit;
        String modifiedBy = stringOf(audit, "modifiedBy");
        Long modifiedTime = longOf(audit, "modifiedTime");
        return AuditDetails.builder()
                .createdBy(stringOf(audit, "createdBy"))
                .createdTime(longOf(audit, "createdTime"))
                .lastModifiedBy(modifiedBy == null ? stringOf(audit, "lastModifiedBy") : modifiedBy)
                .lastModifiedTime(modifiedTime == null ? longOf(audit, "lastModifiedTime") : modifiedTime)
                .build();
    }

    /**
     * v3 stores an assignee as a bare user-id string; 2.x wants a whole User.
     *
     * <p>Only the uuid is set. {@code WorkflowService.getWorkflow} maps {@code User::getUuid} and
     * {@code NotificationService} re-fetches the person by uuid, so pgr-services is unaffected;
     * anything reading {@code assignes[0].getName()} straight off a workflow reply gets null.
     */
    private List<User> assigneesOf(Map<?, ?> v3) {
        Object raw = valueOf(v3, "assignees");
        if (!(raw instanceof List))
            return null;
        List<User> users = new ArrayList<>();
        for (Object uuid : (List<?>) raw) {
            User user = userOf(uuid instanceof String ? (String) uuid : null);
            if (user != null)
                users.add(user);
        }
        return users.isEmpty() ? null : users;
    }

    private User userOf(String uuid) {
        if (uuid == null || uuid.trim().isEmpty())
            return null;
        User user = new User();
        user.setUuid(uuid);
        return user;
    }

    private List<Document> documentsOf(Map<?, ?> v3) {
        Object raw = valueOf(v3, "documents");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty())
            return null;
        try {
            return mapper.convertValue(raw, new TypeReference<List<Document>>() {
            });
        } catch (IllegalArgumentException e) {
            log.error("v3 workflow returned a documents array pgr-services cannot read: {}", raw, e);
            throw new CustomException("WORKFLOW_RESPONSE_UNREADABLE",
                    "v3 workflow returned a documents array that does not bind to the 2.x Document model: " + raw);
        }
    }

    // ------------------------------------------------------------------ null-tolerant readers

    /**
     * A case-insensitive lookup, because {@code PGRApp} configures the shared ObjectMapper with
     * ACCEPT_CASE_INSENSITIVE_PROPERTIES and the rest of this codebase is therefore already
     * tolerant of a capitalised key. Reading a raw Map here would otherwise be stricter than
     * reading the same reply through the mapper, which would be a surprise rather than a safeguard.
     */
    private static Object valueOf(Map<?, ?> map, String key) {
        if (map == null)
            return null;
        Object direct = map.get(key);
        if (direct != null)
            return direct;
        for (Map.Entry<?, ?> entry : map.entrySet())
            if (entry.getKey() instanceof String && key.equalsIgnoreCase((String) entry.getKey()))
                return entry.getValue();
        return null;
    }

    private static String stringOf(Map<?, ?> map, String key) {
        Object value = valueOf(map, key);
        return value instanceof String ? (String) value : null;
    }

    private static Long longOf(Map<?, ?> map, String key) {
        Object value = valueOf(map, key);
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private static Boolean booleanOf(Map<?, ?> map, String key) {
        Object value = valueOf(map, key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static List<String> stringsOf(Map<?, ?> map, String key) {
        Object value = valueOf(map, key);
        if (!(value instanceof List))
            return null;
        List<String> out = new ArrayList<>();
        for (Object item : (List<?>) value)
            if (item instanceof String)
                out.add((String) item);
        return out.isEmpty() ? null : out;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    /** An empty v3 search envelope, for the callers that need one when nothing was asked for. */
    public static Map<String, Object> emptySearchEnvelope() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put(TOTAL_COUNT_KEY, 0);
        empty.put(PROCESS_INSTANCES_KEY, Collections.emptyList());
        return empty;
    }
}
