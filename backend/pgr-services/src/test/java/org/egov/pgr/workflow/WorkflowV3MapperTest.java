package org.egov.pgr.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.pgr.web.models.workflow.Action;
import org.egov.pgr.web.models.workflow.BusinessService;
import org.egov.pgr.web.models.workflow.ProcessInstance;
import org.egov.pgr.web.models.workflow.State;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v3 → 2.x workflow mapping, against replies captured verbatim from the running DIGIT 3.0
 * stack on 2026-09-21 (Kong :8000, tenant {@code pg}, the registered PGR process definition).
 *
 * <p>These are the real bytes, not a reading of {@code digit-specs/v3.0.0/workflow.yaml}: the spec
 * and the shipped build disagree, and on this dependency they disagree about a field
 * ({@code roles} on an action) that the service silently enforces and the spec does not declare.
 */
class WorkflowV3MapperTest {

    /** POST /workflow/v3/transition — captured. Note it is ONE object, not a list. */
    private static final String V3_TRANSITION_RESPONSE =
            "{\"id\":\"a7c7b925-60ff-4e6b-a46f-2228bc904922\",\"processCode\":\"PGR\",\"entityId\":\"PROBE-C9-001\","
                    + "\"action\":\"ASSIGN\",\"comment\":\"\",\"documents\":null,\"assigner\":\"\","
                    + "\"assignees\":[\"emp-uuid-1\"],\"currentState\":\"PENDINGATLME\",\"stateSla\":300000,"
                    + "\"processSla\":432000000,\"auditDetail\":{\"createdBy\":\"probe-gro\",\"createdTime\":1789963134933,"
                    + "\"modifiedBy\":\"probe-gro\",\"modifiedTime\":1789963134933}}";

    /** GET /workflow/v3/transition?processCode=PGR&entityId=…&history=true — captured, oldest-first. */
    private static final String V3_HISTORY_RESPONSE =
            "{\"totalCount\":2,\"page\":0,\"size\":100,\"processInstances\":["
                    + "{\"id\":\"c81d6523\",\"processCode\":\"PGR\",\"entityId\":\"PROBE-C9-001\",\"action\":\"APPLY\","
                    + "\"comment\":\"probe\",\"currentState\":\"PENDINGFORASSIGNMENT\",\"escalated\":false,"
                    + "\"auditDetail\":{\"createdBy\":\"probe-user\",\"createdTime\":1789963090747,"
                    + "\"modifiedBy\":\"probe-user\",\"modifiedTime\":1789963090747}},"
                    + "{\"id\":\"a7c7b925\",\"processCode\":\"PGR\",\"entityId\":\"PROBE-C9-001\",\"action\":\"ASSIGN\","
                    + "\"assignees\":[\"emp-uuid-1\"],\"currentState\":\"PENDINGATLME\",\"isLatest\":true,"
                    + "\"escalated\":false,\"auditDetail\":{\"createdBy\":\"probe-gro\",\"createdTime\":1789963134933,"
                    + "\"modifiedBy\":\"probe-gro\",\"modifiedTime\":1789963134933}}]}";

    /** GET /workflow/v3/process/definition?processCode=PGR — captured, trimmed to three states. */
    private static final String V3_DEFINITION_RESPONSE =
            "{\"definitions\":[{\"id\":\"5b901ad2\",\"name\":\"PGR\",\"code\":\"PGR\",\"version\":\"1.0\","
                    + "\"sla\":432000000,\"auditDetail\":{\"createdBy\":\"seed\",\"createdTime\":1789877943809,"
                    + "\"modifiedBy\":\"seed\",\"modifiedTime\":1789877943809},\"states\":["
                    + "{\"id\":\"fe77f098\",\"processId\":\"5b901ad2\",\"code\":\"START\",\"name\":\"Start\","
                    + "\"type\":\"INITIAL\",\"isInitial\":true,\"actions\":[{\"id\":\"c5520ea5\",\"code\":\"APPLY\","
                    + "\"label\":\"Apply\",\"currentState\":\"fe77f098\",\"nextState\":\"5136fa2e\","
                    + "\"roles\":[\"CITIZEN\",\"CSR\"]}]},"
                    + "{\"id\":\"5136fa2e\",\"processId\":\"5b901ad2\",\"code\":\"PENDINGFORASSIGNMENT\","
                    + "\"type\":\"INTERMEDIATE\",\"actions\":[{\"id\":\"7ea9bc27\",\"code\":\"ASSIGN\","
                    + "\"currentState\":\"5136fa2e\",\"nextState\":\"cac69123\",\"roles\":[\"GRO\",\"PGR_VIEWER\"]}]},"
                    + "{\"id\":\"2c008d2a\",\"processId\":\"5b901ad2\",\"code\":\"RESOLVED\",\"type\":\"INTERMEDIATE\","
                    + "\"sla\":300000}],\"page\":0,\"size\":1,\"totalCount\":1}]}";

    private ObjectMapper objectMapper;
    private WorkflowV3Mapper v3Mapper;

    @BeforeEach
    void setUp() {
        // Configured exactly as PGRApp configures the production bean. That configuration is the
        // reason a wrong shape is silent in this codebase, so a stricter mapper here would prove
        // something about a service that is not the one being shipped.
        objectMapper = new ObjectMapper()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        v3Mapper = new WorkflowV3Mapper(objectMapper);
    }

    private Map<String, Object> json(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ process instances

    @Test
    @DisplayName("a v3 transition response becomes a 2.x ProcessInstance, every rename applied")
    void transitionResponseMapsToAProcessInstance() {
        ProcessInstance out = v3Mapper.processInstance(json(V3_TRANSITION_RESPONSE), "ke.bomet", "PGR",
                "PGR", "actor-uuid", "POST /workflow/v3/transition");

        assertEquals("a7c7b925-60ff-4e6b-a46f-2228bc904922", out.getId());
        assertEquals("ke.bomet", out.getTenantId());
        // processCode -> businessService, entityId -> businessId
        assertEquals("PGR", out.getBusinessService());
        assertEquals("PROBE-C9-001", out.getBusinessId());
        assertEquals("ASSIGN", out.getAction());
        // processSla -> businesssServiceSla, three s's, as upstream spells it
        assertEquals(432000000L, out.getBusinesssServiceSla());
    }

    @Test
    @DisplayName("currentState, a bare code string, becomes the whole 2.x State object")
    void currentStateBecomesTheStateObject() {
        ProcessInstance out = v3Mapper.processInstance(json(V3_TRANSITION_RESPONSE), "ke.bomet", "PGR",
                "PGR", "actor-uuid", "POST /workflow/v3/transition");

        // applicationStatus is the one field the 2.x contract cannot do without.
        assertEquals("PENDINGATLME", out.getState().getApplicationStatus());
        assertEquals("PENDINGATLME", out.getState().getState());
        // stateSla lands on State.sla; ProcessInstance has no field for it.
        assertEquals(300000L, out.getState().getSla());
        // Everything else 2.x staples onto a transition reply is absent from v3 and stays null.
        assertNull(out.getState().getUuid());
        assertNull(out.getState().getActions());
        assertNull(out.getState().getIsTerminateState());
        assertNull(out.getState().getDocUploadRequired());
    }

    @Test
    @DisplayName("a row with no state spelling at all throws rather than writing a null status")
    void aRowWithNoStateThrows() {
        CustomException thrown = assertThrows(CustomException.class,
                () -> v3Mapper.processInstance(json("{\"id\":\"x\",\"entityId\":\"PG-1\",\"action\":\"APPLY\"}"),
                        "ke.bomet", "PGR", "PGR", "actor", "GET /workflow/v3/transition"));

        assertEquals("WORKFLOW_STATE_MISSING", thrown.getCode());
        assertTrue(thrown.getMessage().contains("currentState"));
    }

    @Test
    @DisplayName("a nested state.code is accepted, so a later v3 build cannot quietly report null")
    void aNestedStateCodeIsAccepted() {
        ProcessInstance out = v3Mapper.processInstance(
                json("{\"entityId\":\"PG-1\",\"action\":\"APPLY\",\"state\":{\"code\":\"RESOLVED\"}}"),
                "ke.bomet", "PGR", "PGR", "actor", "GET /workflow/v3/transition");

        assertEquals("RESOLVED", out.getState().getApplicationStatus());
    }

    @Test
    @DisplayName("assignees, bare uuid strings in v3, become 2.x User objects carrying only the uuid")
    void assigneesBecomeUsers() {
        ProcessInstance out = v3Mapper.processInstance(json(V3_TRANSITION_RESPONSE), "ke.bomet", "PGR",
                "PGR", "actor-uuid", "POST /workflow/v3/transition");

        assertEquals(1, out.getAssignes().size());
        assertEquals("emp-uuid-1", out.getAssignes().get(0).getUuid());
        // v3's workflow store holds nothing else about the person; NotificationService re-fetches.
        assertNull(out.getAssignes().get(0).getName());
    }

    @Test
    @DisplayName("v3 blanks assigner, so the acting user from the request stands in for it")
    void blankAssignerFallsBackToTheActingUser() {
        ProcessInstance out = v3Mapper.processInstance(json(V3_TRANSITION_RESPONSE), "ke.bomet", "PGR",
                "PGR", "actor-uuid", "POST /workflow/v3/transition");

        assertEquals("actor-uuid", out.getAssigner().getUuid());
    }

    @Test
    @DisplayName("an empty comment becomes null, as 2.x reports it")
    void emptyCommentBecomesNull() {
        ProcessInstance out = v3Mapper.processInstance(json(V3_TRANSITION_RESPONSE), "ke.bomet", "PGR",
                "PGR", "actor-uuid", "POST /workflow/v3/transition");

        assertNull(out.getComment());
    }

    @Test
    @DisplayName("auditDetail.modifiedBy/modifiedTime become auditDetails.lastModifiedBy/lastModifiedTime")
    void auditIsRenamed() {
        ProcessInstance out = v3Mapper.processInstance(json(V3_TRANSITION_RESPONSE), "ke.bomet", "PGR",
                "PGR", "actor-uuid", "POST /workflow/v3/transition");

        assertEquals("probe-gro", out.getAuditDetails().getCreatedBy());
        assertEquals(1789963134933L, out.getAuditDetails().getCreatedTime());
        assertEquals("probe-gro", out.getAuditDetails().getLastModifiedBy());
        assertEquals(1789963134933L, out.getAuditDetails().getLastModifiedTime());
    }

    @Test
    @DisplayName("moduleName is echoed from the request; v3 has no such field")
    void moduleNameIsEchoed() {
        ProcessInstance out = v3Mapper.processInstance(json(V3_TRANSITION_RESPONSE), "ke.bomet", "PGR",
                "PGR", "actor-uuid", "POST /workflow/v3/transition");

        assertEquals("PGR", out.getModuleName());
    }

    // ------------------------------------------------------------------ envelopes

    @Test
    @DisplayName("the search envelope's processInstances rows are read in order, oldest first")
    void historyRowsAreReadInOrder() {
        List<Map<String, Object>> rows = v3Mapper.rowsOf(json(V3_HISTORY_RESPONSE),
                WorkflowV3Mapper.PROCESS_INSTANCES_KEY, "GET /workflow/v3/transition");

        assertEquals(2, rows.size());
        assertEquals("APPLY", v3Mapper.processInstance(rows.get(0), "ke", "PGR", null, null, "x").getAction());
        assertEquals("ASSIGN", v3Mapper.processInstance(rows.get(1), "ke", "PGR", null, null, "x").getAction());
    }

    @Test
    @DisplayName("a present but empty rows key is a legitimate empty answer")
    void anEmptyRowsKeyIsNotAFailure() {
        assertTrue(v3Mapper.rowsOf(json("{\"totalCount\":0,\"processInstances\":[]}"),
                WorkflowV3Mapper.PROCESS_INSTANCES_KEY, "GET /workflow/v3/transition").isEmpty());
    }

    @Test
    @DisplayName("an ABSENT rows key is an unreadable reply and throws — it is not an empty answer")
    void aMissingRowsKeyThrows() {
        CustomException thrown = assertThrows(CustomException.class,
                () -> v3Mapper.rowsOf(json("{\"totalCount\":0,\"ProcessInstanceList\":[]}"),
                        WorkflowV3Mapper.PROCESS_INSTANCES_KEY, "GET /workflow/v3/transition"));

        assertEquals("WORKFLOW_RESPONSE_UNREADABLE", thrown.getCode());
        // The keys that WERE there are named, because that is the whole diagnosis.
        assertTrue(thrown.getMessage().contains("ProcessInstanceList"));
    }

    @Test
    @DisplayName("totalCount is read off the envelope, and a short collection is refused")
    void truncationIsRefused() {
        assertEquals(2L, v3Mapper.totalCountOf(json(V3_HISTORY_RESPONSE)));

        CustomException thrown = assertThrows(CustomException.class,
                () -> v3Mapper.assertNotTruncated(137L, 100, "GET /workflow/v3/transition"));
        assertEquals("WORKFLOW_RESULT_TRUNCATED", thrown.getCode());
        assertTrue(thrown.getMessage().contains("137"));
    }

    @Test
    @DisplayName("a complete collection passes, and an envelope with no totalCount cannot be judged")
    void aCompleteCollectionPasses() {
        v3Mapper.assertNotTruncated(2L, 2, "GET /workflow/v3/transition");
        v3Mapper.assertNotTruncated(null, 0, "GET /workflow/v3/transition");
    }

    // ------------------------------------------------------------------ process definitions

    @Test
    @DisplayName("a v3 definition becomes a 2.x BusinessService: id->uuid, code->businessService, sla->businessServiceSla")
    void definitionBecomesABusinessService() {
        List<Map<String, Object>> defs = v3Mapper.rowsOf(json(V3_DEFINITION_RESPONSE),
                WorkflowV3Mapper.DEFINITIONS_KEY, "GET /workflow/v3/process/definition");
        BusinessService out = v3Mapper.businessService(defs.get(0), "ke.bomet");

        assertEquals("5b901ad2", out.getUuid());
        assertEquals("PGR", out.getBusinessService());
        assertEquals("ke.bomet", out.getTenantId());
        assertEquals(432000000L, out.getBusinessServiceSla());
        assertEquals(3, out.getStates().size());
    }

    @Test
    @DisplayName("the invented START code is turned back into the 2.x nameless start state")
    void startBecomesNullAgain() {
        List<Map<String, Object>> defs = v3Mapper.rowsOf(json(V3_DEFINITION_RESPONSE),
                WorkflowV3Mapper.DEFINITIONS_KEY, "GET /workflow/v3/process/definition");
        State start = v3Mapper.businessService(defs.get(0), "ke.bomet").getStates().get(0);

        // v3 forbids an empty state code and the registration calls it START; 2.x names it null,
        // and MigrationUtils keys a map on that spelling.
        assertNull(start.getState());
        assertNull(start.getApplicationStatus());
        assertEquals(Boolean.TRUE, start.getIsStartState());
        assertEquals("fe77f098", start.getUuid());
    }

    @Test
    @DisplayName("a definition state keeps its code, its uuid and its owning process")
    void definitionStatesCarryTheirIdentity() {
        List<Map<String, Object>> defs = v3Mapper.rowsOf(json(V3_DEFINITION_RESPONSE),
                WorkflowV3Mapper.DEFINITIONS_KEY, "GET /workflow/v3/process/definition");
        State pending = v3Mapper.businessService(defs.get(0), "ke.bomet").getStates().get(1);

        assertEquals("PENDINGFORASSIGNMENT", pending.getState());
        assertEquals("PENDINGFORASSIGNMENT", pending.getApplicationStatus());
        assertEquals("5136fa2e", pending.getUuid());
        assertEquals("5b901ad2", pending.getBusinessServiceId());
        assertEquals(Boolean.FALSE, pending.getIsStartState());
    }

    @Test
    @DisplayName("a definition action: code->action, id->uuid, and the roles v3's spec does not declare")
    void definitionActionsAreMapped() {
        List<Map<String, Object>> defs = v3Mapper.rowsOf(json(V3_DEFINITION_RESPONSE),
                WorkflowV3Mapper.DEFINITIONS_KEY, "GET /workflow/v3/process/definition");
        Action apply = v3Mapper.businessService(defs.get(0), "ke.bomet").getStates().get(0).getActions().get(0);

        assertEquals("APPLY", apply.getAction());
        assertEquals("c5520ea5", apply.getUuid());
        assertEquals("fe77f098", apply.getCurrentState());
        assertEquals("5136fa2e", apply.getNextState());
        // Undeclared in digit-specs/v3.0.0/workflow.yaml, stored and ENFORCED by the shipped build.
        assertEquals(List.of("CITIZEN", "CSR"), apply.getRoles());
    }

    @Test
    @DisplayName("isTerminateState is permanently null under v3, and is not inferred")
    void terminateStateIsNull() {
        List<Map<String, Object>> defs = v3Mapper.rowsOf(json(V3_DEFINITION_RESPONSE),
                WorkflowV3Mapper.DEFINITIONS_KEY, "GET /workflow/v3/process/definition");
        // RESOLVED is the 2.x terminal-ish state; v3 has no state-type column at all and rewrites
        // TERMINAL_SUCCESS to INTERMEDIATE on write. CCRS takes completion from applicationStatus.
        State resolved = v3Mapper.businessService(defs.get(0), "ke.bomet").getStates().get(2);

        assertEquals("RESOLVED", resolved.getState());
        assertNull(resolved.getIsTerminateState());
        assertNull(resolved.getActions());
    }

    @Test
    @DisplayName("BusinessService.business and the two Uri fields have no v3 source and stay null")
    void fieldsWithNoV3SourceStayNull() {
        List<Map<String, Object>> defs = v3Mapper.rowsOf(json(V3_DEFINITION_RESPONSE),
                WorkflowV3Mapper.DEFINITIONS_KEY, "GET /workflow/v3/process/definition");
        BusinessService out = v3Mapper.businessService(defs.get(0), "ke.bomet");

        // additionalDetails is declared free-form in the spec and does not exist in the build, so
        // there is nowhere to park any of these.
        assertNull(out.getBusiness());
        assertNull(out.getGetUri());
        assertNull(out.getPostUri());
    }

    @Test
    @DisplayName("a capitalised envelope key still reads, because the production mapper is case-insensitive")
    void envelopeKeysAreCaseTolerant() {
        assertEquals(1, v3Mapper.rowsOf(json("{\"ProcessInstances\":[{\"currentState\":\"RESOLVED\"}]}"),
                WorkflowV3Mapper.PROCESS_INSTANCES_KEY, "GET /workflow/v3/transition").size());
    }

    @Test
    @DisplayName("a null body is an unreadable reply, not an empty one")
    void aNullBodyThrows() {
        CustomException thrown = assertThrows(CustomException.class,
                () -> v3Mapper.rowsOf(null, WorkflowV3Mapper.PROCESS_INSTANCES_KEY, "GET /workflow/v3/transition"));
        assertEquals("WORKFLOW_RESPONSE_UNREADABLE", thrown.getCode());
    }
}
