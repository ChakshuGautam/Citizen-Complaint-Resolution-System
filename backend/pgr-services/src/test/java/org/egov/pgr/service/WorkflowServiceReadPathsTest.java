package org.egov.pgr.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.Role;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.workflow.BusinessService;
import org.egov.pgr.web.models.workflow.ProcessInstance;
import org.egov.tracer.model.CustomException;
import org.egov.tracer.model.ServiceCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The two workflow read paths, on BOTH stacks.
 *
 * <p>Both branches are exercised deliberately. A version flag has to be able to go back: a
 * deployment that flips to v3, finds the new stack wrong and reverts must get yesterday's call
 * byte for byte, and a branch that nothing exercises rots. So the 2.x request is asserted
 * character-for-character here for the first time — until now nothing in this repository asserted
 * what pgr-services SENDS to the workflow service, only what it does with a hand-built reply.
 */
class WorkflowServiceReadPathsTest {

    private static final String TENANT = "ke.bomet";
    private static final String ACTOR = "employee-uuid";
    private static final String ID_A = "PG-PGR-2026-09-21-900044";
    private static final String ID_B = "PG-PGR-2026-09-21-900045";

    private static final String V3_DEFINITION =
            "{\"definitions\":[{\"id\":\"d1\",\"code\":\"PGR\",\"sla\":432000000,\"states\":["
                    + "{\"id\":\"s1\",\"code\":\"START\",\"type\":\"INITIAL\",\"isInitial\":true},"
                    + "{\"id\":\"s2\",\"code\":\"PENDINGFORASSIGNMENT\",\"type\":\"INTERMEDIATE\"}]}],"
                    + "\"page\":0,\"size\":1,\"totalCount\":1}";

    private ObjectMapper objectMapper;
    private MockRestServiceServer server;
    private PGRConfiguration config;
    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
        restTemplate.getMessageConverters().add(new MappingJackson2HttpMessageConverter(objectMapper));
        server = MockRestServiceServer.createServer(restTemplate);

        config = new PGRConfiguration();
        config.setWfHost("http://localhost:8084");
        config.setWfBusinessServiceSearchPath("/egov-workflow-v2/egov-wf/businessservice/_search");
        config.setWfProcessInstanceSearchPath("/egov-workflow-v2/egov-wf/process/_search");
        config.setWfTransitionPath("/egov-workflow-v2/egov-wf/process/_transition");
        config.setWorkflowV3Host("http://localhost:8084");
        config.setWorkflowV3ProcessDefinitionSearchPath("/workflow/v3/process/definition");
        config.setWorkflowV3TransitionPath("/workflow/v3/transition");
        config.setV3PageMaxSize(100);
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_2X);

        workflowService = new WorkflowService(config, new ServiceRequestRepository(objectMapper, restTemplate), objectMapper);
    }

    private void flipToV3() {
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_V3);
    }

    private RequestInfo requestInfo() {
        return requestInfo(ACTOR);
    }

    private RequestInfo requestInfo(String actorUuid) {
        User userInfo = User.builder()
                .uuid(actorUuid)
                .type("EMPLOYEE")
                .tenantId(TENANT)
                .roles(Collections.singletonList(Role.builder().code("PGR_LME").build()))
                .build();
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setApiId("Rainmaker");
        requestInfo.setAuthToken("gateway-issued-token");
        requestInfo.setUserInfo(userInfo);
        return requestInfo;
    }

    private static String processRow(String entityId, String action, String state) {
        return "{\"id\":\"t-" + entityId + "\",\"processCode\":\"PGR\",\"entityId\":\"" + entityId + "\",\"action\":\""
                + action + "\",\"currentState\":\"" + state + "\",\"assignees\":[\"assignee-uuid\"],\"assigner\":\"\","
                + "\"auditDetail\":{\"createdBy\":\"sys\",\"createdTime\":1695000000000,\"modifiedBy\":\"sys\","
                + "\"modifiedTime\":1695000000000}}";
    }

    // ------------------------------------------------------------------ business service search

    @Test
    @DisplayName("2.x: the business service search is a POST carrying tenantId, businessServices and a RequestInfo body")
    void twoXBusinessServiceSearchIsUnchanged() {
        server.expect(requestTo("http://localhost:8084/egov-workflow-v2/egov-wf/businessservice/_search"
                        + "?tenantId=ke.bomet&businessServices=PGR"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("RequestInfo")))
                .andRespond(withSuccess("{\"BusinessServices\":[{\"businessService\":\"PGR\",\"uuid\":\"u1\","
                        + "\"states\":[{\"uuid\":\"s2\",\"state\":\"PENDINGFORASSIGNMENT\"}]}]}", MediaType.APPLICATION_JSON));

        BusinessService out = workflowService.getBusinessService(requestInfo(), TENANT);
        server.verify();

        assertEquals("PGR", out.getBusinessService());
    }

    @Test
    @DisplayName("v3: the business service search is a GET on processCode with the tenant in a header and no body")
    void v3BusinessServiceSearch() {
        flipToV3();
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8084/workflow/v3/process/definition?"),
                        containsString("processCode=PGR"),
                        not(containsString("businessServices=")),
                        not(containsString("tenantId=")))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-ID", TENANT))
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess(V3_DEFINITION, MediaType.APPLICATION_JSON));

        BusinessService out = workflowService.getBusinessService(requestInfo(), TENANT);
        server.verify();

        assertEquals("PGR", out.getBusinessService());
        assertEquals("d1", out.getUuid());
        assertEquals(432000000L, out.getBusinessServiceSla());
    }

    @Test
    @DisplayName("v3: a definition reply with no definitions key is unreadable, not an empty result")
    void v3BusinessServiceSearchRefusesAnUnknownEnvelope() {
        flipToV3();
        server.expect(requestTo(startsWith("http://localhost:8084/workflow/v3/process/definition")))
                .andRespond(withSuccess("{\"BusinessServices\":[],\"totalCount\":0}", MediaType.APPLICATION_JSON));

        CustomException thrown = assertThrows(CustomException.class,
                () -> workflowService.getBusinessService(requestInfo(), TENANT));

        assertEquals("WORKFLOW_RESPONSE_UNREADABLE", thrown.getCode());
    }

    // ------------------------------------------------------------------ process instance search

    @Test
    @DisplayName("2.x: one complaint is a POST with ?tenantId=&businessIds=")
    void twoXProcessSearchIsUnchanged() {
        server.expect(requestTo("http://localhost:8084/egov-workflow-v2/egov-wf/process/_search"
                        + "?tenantId=ke.bomet&businessIds=" + ID_A))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ProcessInstances\":[{\"businessId\":\"" + ID_A + "\",\"action\":\"APPLY\"}]}",
                        MediaType.APPLICATION_JSON));

        List<ProcessInstance> out = workflowService.searchProcessInstances(requestInfo(), TENANT, List.of(ID_A));
        server.verify();

        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("2.x: two complaints still ride one call with a comma-separated businessIds")
    void twoXProcessSearchStillJoinsInOneCall() {
        server.expect(requestTo("http://localhost:8084/egov-workflow-v2/egov-wf/process/_search"
                        + "?tenantId=ke.bomet&businessIds=" + ID_A + "," + ID_B))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ProcessInstances\":[{\"businessId\":\"" + ID_A + "\"},"
                        + "{\"businessId\":\"" + ID_B + "\"}]}", MediaType.APPLICATION_JSON));

        assertEquals(2, workflowService.searchProcessInstances(requestInfo(), TENANT, List.of(ID_A, ID_B)).size());
        server.verify();
    }

    @Test
    @DisplayName("v3: one complaint is a GET carrying the mandatory processCode, the entityId and both headers")
    void v3ProcessSearch() {
        flipToV3();
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8084/workflow/v3/transition?"),
                        containsString("processCode=PGR"),
                        containsString("entityId=" + ID_A),
                        containsString("size=100"),
                        not(containsString("businessIds=")),
                        not(containsString("history=")),
                        not(containsString("tenantId=")))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-ID", TENANT))
                .andExpect(header("X-User-ID", ACTOR))
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess("{\"totalCount\":1,\"page\":0,\"size\":100,\"processInstances\":["
                        + processRow(ID_A, "ASSIGN", "PENDINGATLME") + "]}", MediaType.APPLICATION_JSON));

        List<ProcessInstance> out = workflowService.searchProcessInstances(requestInfo(), TENANT, List.of(ID_A));
        server.verify();

        assertEquals(1, out.size());
        // entityId -> businessId is what keys the inbox join; currentState -> applicationStatus is
        // the field the whole contract turns on.
        assertEquals(ID_A, out.get(0).getBusinessId());
        assertEquals("PENDINGATLME", out.get(0).getState().getApplicationStatus());
        assertEquals("assignee-uuid", out.get(0).getAssignes().get(0).getUuid());
    }

    @Test
    @DisplayName("v3: TWO complaints become TWO single-entity calls — the comma form is never sent")
    void v3FansOutAMultiComplaintInbox() {
        flipToV3();
        // v3 answers ?entityId=a,b with totalCount 0 and ?entityId=a&entityId=b with only the
        // first id, both at HTTP 200, so ASKING for two in one call is how an employee inbox
        // loses complaints silently. One call per complaint, and both must come back.
        server.expect(requestTo(allOf(containsString("entityId=" + ID_A), not(containsString(",")))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"totalCount\":1,\"processInstances\":["
                        + processRow(ID_A, "APPLY", "PENDINGFORASSIGNMENT") + "]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(allOf(containsString("entityId=" + ID_B), not(containsString(",")))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"totalCount\":1,\"processInstances\":["
                        + processRow(ID_B, "ASSIGN", "PENDINGATLME") + "]}", MediaType.APPLICATION_JSON));

        List<ProcessInstance> out = workflowService.searchProcessInstances(requestInfo(), TENANT, List.of(ID_A, ID_B));
        server.verify();

        assertEquals(List.of(ID_A, ID_B),
                List.of(out.get(0).getBusinessId(), out.get(1).getBusinessId()));
    }

    @Test
    @DisplayName("v3: the history search adds history=true and reads the rows oldest-first")
    void v3HistorySearch() {
        flipToV3();
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8084/workflow/v3/transition?"),
                        containsString("entityId=" + ID_A),
                        containsString("history=true"))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"totalCount\":2,\"page\":0,\"size\":100,\"processInstances\":["
                        + processRow(ID_A, "APPLY", "PENDINGFORASSIGNMENT") + ","
                        + processRow(ID_A, "ASSIGN", "PENDINGATLME") + "]}", MediaType.APPLICATION_JSON));

        List<ProcessInstance> out = workflowService.searchProcessInstanceHistory(requestInfo(), TENANT, ID_A);
        server.verify();

        assertEquals(List.of("APPLY", "ASSIGN"), List.of(out.get(0).getAction(), out.get(1).getAction()));
    }

    @Test
    @DisplayName("2.x: the history search is still the same URL with &history=true appended")
    void twoXHistorySearchIsUnchanged() {
        server.expect(requestTo("http://localhost:8084/egov-workflow-v2/egov-wf/process/_search"
                        + "?tenantId=ke.bomet&businessIds=" + ID_A + "&history=true"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ProcessInstances\":[{\"businessId\":\"" + ID_A + "\",\"action\":\"APPLY\"}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals(1, workflowService.searchProcessInstanceHistory(requestInfo(), TENANT, ID_A).size());
        server.verify();
    }

    @Test
    @DisplayName("v3: the assignee inbox is a GET on assignee with no entityId")
    void v3AssigneeSearch() {
        flipToV3();
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8084/workflow/v3/transition?"),
                        containsString("processCode=PGR"),
                        containsString("assignee=assignee-uuid"),
                        not(containsString("entityId=")),
                        not(containsString("businessService=")))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"totalCount\":2,\"page\":0,\"size\":100,\"processInstances\":["
                        + processRow(ID_A, "ASSIGN", "PENDINGATLME") + ","
                        + processRow(ID_B, "ASSIGN", "PENDINGATLME") + "]}", MediaType.APPLICATION_JSON));

        Set<String> ids = workflowService.getServiceRequestIdsByAssignee(requestInfo(), TENANT, "assignee-uuid");
        server.verify();

        assertEquals(Set.of(ID_A, ID_B), ids);
    }

    @Test
    @DisplayName("2.x: the assignee inbox still sends businessService=PGR and assignee= on the query")
    void twoXAssigneeSearchIsUnchanged() {
        server.expect(requestTo("http://localhost:8084/egov-workflow-v2/egov-wf/process/_search"
                        + "?tenantId=ke.bomet&businessService=PGR&assignee=assignee-uuid"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ProcessInstances\":[{\"businessId\":\"" + ID_A + "\"}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals(Set.of(ID_A), workflowService.getServiceRequestIdsByAssignee(requestInfo(), TENANT, "assignee-uuid"));
        server.verify();
    }

    // ------------------------------------------------------------------ the quiet failures

    @Test
    @DisplayName("v3: a truncated list is refused rather than handed on as a complete short one")
    void v3TruncationIsRefused() {
        flipToV3();
        server.expect(requestTo(startsWith("http://localhost:8084/workflow/v3/transition")))
                .andRespond(withSuccess("{\"totalCount\":137,\"page\":0,\"size\":100,\"processInstances\":["
                        + processRow(ID_A, "ASSIGN", "PENDINGATLME") + "]}", MediaType.APPLICATION_JSON));

        CustomException thrown = assertThrows(CustomException.class,
                () -> workflowService.getServiceRequestIdsByAssignee(requestInfo(), TENANT, "assignee-uuid"));

        assertEquals("WORKFLOW_RESULT_TRUNCATED", thrown.getCode());
        assertThat(thrown.getMessage(), containsString("137"));
    }

    @Test
    @DisplayName("v3: a reply with a renamed rows key throws instead of binding an empty list")
    void v3UnknownEnvelopeKeyIsRefused() {
        flipToV3();
        server.expect(requestTo(startsWith("http://localhost:8084/workflow/v3/transition")))
                .andRespond(withSuccess("{\"totalCount\":1,\"transitions\":[" + processRow(ID_A, "ASSIGN", "X") + "]}",
                        MediaType.APPLICATION_JSON));

        CustomException thrown = assertThrows(CustomException.class,
                () -> workflowService.searchProcessInstances(requestInfo(), TENANT, List.of(ID_A)));

        assertEquals("WORKFLOW_RESPONSE_UNREADABLE", thrown.getCode());
        assertThat(thrown.getMessage(), containsString("transitions"));
    }

    @Test
    @DisplayName("v3: no acting user uuid means no X-User-ID, which this endpoint answers 400 — so it is refused first")
    void v3RefusesToCallWithoutAnActor() {
        flipToV3();
        CustomException thrown = assertThrows(CustomException.class,
                () -> workflowService.searchProcessInstances(requestInfo(null), TENANT, List.of(ID_A)));

        assertEquals("WORKFLOW_ACTOR_MISSING", thrown.getCode());
        server.verify();
    }

    @Test
    @DisplayName("v3: an upstream failure carries v3's own error text, which lives in getError()")
    void v3FailureKeepsItsDiagnosis() {
        flipToV3();
        server.expect(requestTo(startsWith("http://localhost:8084/workflow/v3/transition")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("[{\"code\":\"ValidationError\",\"message\":\"processCode query parameter is required "
                                + "when filtering by entityId\"}]"));

        ServiceCallException thrown = assertThrows(ServiceCallException.class,
                () -> workflowService.searchProcessInstances(requestInfo(), TENANT, List.of(ID_A)));

        // getMessage() is always null on this class; the text is in getError().
        assertTrue(thrown.getError().contains("processCode query parameter is required"));
    }
}
