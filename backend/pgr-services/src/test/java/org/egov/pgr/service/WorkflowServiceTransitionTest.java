package org.egov.pgr.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.Role;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.Service;
import org.egov.pgr.web.models.ServiceRequest;
import org.egov.pgr.web.models.Workflow;
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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The workflow transition — the irreversible call, on both stacks.
 *
 * <p>The request shape is asserted here rather than in {@code V3WorkflowContractTest}, whose
 * transition test stays @Disabled. The difference is deliberate and is the honest part of this
 * commit: the contract test asserts a KEYCLOAK-ISSUED bearer token, and CCRS's gateway issues an
 * opaque 2.x token. This file asserts what CCRS actually produces — the verb, the path, the body,
 * X-Tenant-ID, X-User-ID and that the caller's existing token is forwarded as a Bearer — and makes
 * no claim about what is inside that token. It also pins the consequence: against the live v3
 * build, an action that declares roles is answered 403 unless the token carries
 * {@code realm_access.roles}, and every PGR action declares roles. So the v3 transition is wired,
 * correct on the wire, and cannot succeed until the identity work lands. That is asserted too,
 * rather than left as a comment.
 */
class WorkflowServiceTransitionTest {

    private static final String TENANT = "ke.bomet";
    private static final String ACTOR = "employee-uuid";
    private static final String TOKEN = "gateway-issued-token";
    private static final String COMPLAINT = "PG-PGR-2026-09-21-900044";

    private static final String V3_DEFINITION =
            "{\"definitions\":[{\"id\":\"d1\",\"code\":\"PGR\",\"sla\":432000000,"
                    + "\"states\":[{\"id\":\"s1\",\"code\":\"START\",\"isInitial\":true}]}],\"totalCount\":1}";

    private MockRestServiceServer server;
    private PGRConfiguration config;
    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
        restTemplate.getMessageConverters().add(new MappingJackson2HttpMessageConverter(objectMapper));
        server = MockRestServiceServer.createServer(restTemplate);

        config = new PGRConfiguration();
        config.setWfHost("http://localhost:8084");
        config.setWfTransitionPath("/egov-workflow-v2/egov-wf/process/_transition");
        config.setWfBusinessServiceSearchPath("/egov-workflow-v2/egov-wf/businessservice/_search");
        config.setWfProcessInstanceSearchPath("/egov-workflow-v2/egov-wf/process/_search");
        config.setWorkflowV3Host("http://localhost:8084");
        config.setWorkflowV3TransitionPath("/workflow/v3/transition");
        config.setWorkflowV3ProcessDefinitionSearchPath("/workflow/v3/process/definition");
        config.setV3PageMaxSize(100);
        config.setWorkflowV3MaxPages(50);
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_2X);

        workflowService = new WorkflowService(config, new ServiceRequestRepository(objectMapper, restTemplate), objectMapper);
    }

    private void flipToV3() {
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_V3);
        // updateWorkflowStatus resolves the business service first, so the definition search comes
        // through before the transition does.
        server.expect(requestTo(startsWith("http://localhost:8084/workflow/v3/process/definition")))
                .andRespond(withSuccess(V3_DEFINITION, MediaType.APPLICATION_JSON));
    }

    private ServiceRequest request(String action, String token) {
        User userInfo = User.builder()
                .uuid(ACTOR)
                .type("EMPLOYEE")
                .tenantId(TENANT)
                .roles(Collections.singletonList(Role.builder().code("PGR_LME").build()))
                .build();
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(userInfo);
        requestInfo.setAuthToken(token);

        return ServiceRequest.builder()
                .requestInfo(requestInfo)
                .service(Service.builder().tenantId(TENANT).serviceRequestId(COMPLAINT).build())
                .workflow(Workflow.builder()
                        .action(action)
                        .comments("assigning to the ward officer")
                        .assignes(Collections.singletonList("assignee-uuid"))
                        .build())
                .build();
    }

    private static String v3TransitionResponse(String state) {
        return "{\"id\":\"t2\",\"processCode\":\"PGR\",\"entityId\":\"" + COMPLAINT + "\",\"action\":\"ASSIGN\","
                + "\"currentState\":\"" + state + "\",\"assignees\":[\"assignee-uuid\"],\"assigner\":\"\","
                + "\"comment\":\"assigning to the ward officer\",\"documents\":null,\"stateSla\":300000,"
                + "\"processSla\":432000000,\"auditDetail\":{\"createdBy\":\"" + ACTOR
                + "\",\"createdTime\":1695000000000,\"modifiedBy\":\"" + ACTOR + "\",\"modifiedTime\":1695000000000}}";
    }

    // ------------------------------------------------------------------ 2.x, pinned

    @Test
    @DisplayName("2.x: the transition is a POST of {RequestInfo, ProcessInstances:[one]} to the 2.x path")
    void twoXTransitionIsUnchanged() {
        server.expect(requestTo("http://localhost:8084/egov-workflow-v2/egov-wf/businessservice/_search"
                        + "?tenantId=ke.bomet&businessServices=PGR"))
                .andRespond(withSuccess("{\"BusinessServices\":[{\"businessService\":\"PGR\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8084/egov-workflow-v2/egov-wf/process/_transition"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("RequestInfo"),
                        containsString("ProcessInstances"),
                        containsString("businessId"),
                        // 2.x overwrites this with the business service's "business"; CCRS sends PGR.
                        containsString("moduleName"))))
                .andRespond(withSuccess("{\"ProcessInstances\":[{\"businessId\":\"" + COMPLAINT
                        + "\",\"state\":{\"applicationStatus\":\"PENDINGATLME\"}}]}", MediaType.APPLICATION_JSON));

        assertEquals("PENDINGATLME", workflowService.updateWorkflowStatus(request("ASSIGN", TOKEN)));
        server.verify();
    }

    // ------------------------------------------------------------------ v3

    @Test
    @DisplayName("v3: one entity, no envelope, assignees as bare uuid strings, roles on a forwarded Bearer")
    void v3TransitionRequestShape() {
        flipToV3();
        server.expect(requestTo("http://localhost:8084/workflow/v3/transition"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Tenant-ID", TENANT))
                .andExpect(header("X-User-ID", ACTOR))
                // Forwarded, never minted. Whether v3 can READ roles out of it is the identity
                // question this commit does not solve; see v3RoleGuardIsLoudUntilIdentityLands.
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().json("{\"processCode\":\"PGR\",\"entityId\":\"" + COMPLAINT
                        + "\",\"action\":\"ASSIGN\",\"comment\":\"assigning to the ward officer\","
                        + "\"assignees\":[\"assignee-uuid\"]}"))
                .andExpect(content().string(allOf(
                        not(containsString("ProcessInstances")),
                        not(containsString("RequestInfo")),
                        not(containsString("businessId")),
                        not(containsString("businessService")),
                        not(containsString("tenantId")),
                        // 2.x replaced CCRS's "PGR" with the business service's "pgr-services";
                        // v3 has neither field, so the baseline diff shows PGR here.
                        not(containsString("moduleName")))))
                .andRespond(withSuccess(v3TransitionResponse("PENDINGATLME"), MediaType.APPLICATION_JSON));

        assertEquals("PENDINGATLME", workflowService.updateWorkflowStatus(request("ASSIGN", TOKEN)));
        server.verify();
    }

    @Test
    @DisplayName("v3: currentState becomes the complaint's applicationStatus, and the ProcessInstance is kept")
    void v3TransitionResponseIsMapped() {
        flipToV3();
        server.expect(requestTo("http://localhost:8084/workflow/v3/transition"))
                .andRespond(withSuccess(v3TransitionResponse("RESOLVED"), MediaType.APPLICATION_JSON));

        ServiceRequest serviceRequest = request("RESOLVE", TOKEN);
        assertEquals("RESOLVED", workflowService.updateWorkflowStatus(serviceRequest));

        assertEquals("RESOLVED", serviceRequest.getService().getApplicationStatus());
        assertEquals(COMPLAINT, serviceRequest.getService().getProcessInstance().getBusinessId());
        assertEquals("assignee-uuid",
                serviceRequest.getService().getProcessInstance().getAssignes().get(0).getUuid());
        server.verify();
    }

    @Test
    @DisplayName("v3: a transition reply with no state code throws instead of writing a null status on the complaint")
    void v3TransitionWithNoStateThrows() {
        flipToV3();
        server.expect(requestTo("http://localhost:8084/workflow/v3/transition"))
                .andRespond(withSuccess("{\"id\":\"t2\",\"entityId\":\"" + COMPLAINT + "\",\"action\":\"ASSIGN\"}",
                        MediaType.APPLICATION_JSON));

        // This is the quietest failure available: updateWorkflowStatus writes
        // state.applicationStatus straight onto the complaint, so a null here is HTTP 200, a
        // statusless complaint and nothing in any log.
        CustomException thrown = assertThrows(CustomException.class,
                () -> workflowService.updateWorkflowStatus(request("ASSIGN", TOKEN)));

        assertEquals("WORKFLOW_STATE_MISSING", thrown.getCode());
    }

    @Test
    @DisplayName("v3: the role guard is loud — a token v3 cannot read roles from is a 403 that reaches the caller")
    void v3RoleGuardIsLoudUntilIdentityLands() {
        flipToV3();
        // This is what the live stack answers today for an opaque 2.x token, or for any JWT
        // without realm_access.roles. Verified 2026-09-21 against the running v3 build, twice.
        server.expect(requestTo("http://localhost:8084/workflow/v3/transition"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("[{\"code\":\"Forbidden\",\"message\":\"caller role does not permit the requested "
                                + "action: guard check failed: caller role does not permit the requested action\"}]"));

        ServiceCallException thrown = assertThrows(ServiceCallException.class,
                () -> workflowService.updateWorkflowStatus(request("ASSIGN", TOKEN)));

        // The message IS the diagnosis, and it survives. getMessage() is always null on this class.
        assertTrue(thrown.getError().contains("caller role does not permit the requested action"));
        assertTrue(thrown.getError().contains("403"));
    }

    @Test
    @DisplayName("v3: with no token at all, no Authorization header is invented")
    void v3NeverMintsAToken() {
        flipToV3();
        server.expect(requestTo("http://localhost:8084/workflow/v3/transition"))
                // The shim forges an unsigned {alg:none} JWT here because the deployed build does
                // not verify signatures. CCRS does not, and an absent header is the honest state.
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(v3TransitionResponse("PENDINGATLME"), MediaType.APPLICATION_JSON));

        assertEquals("PENDINGATLME", workflowService.updateWorkflowStatus(request("ASSIGN", null)));
        server.verify();
    }

    @Test
    @DisplayName("v3: a batch of more than one entity is refused rather than half-applied")
    void v3RefusesABatchTransition() {
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_V3);
        org.egov.pgr.web.models.workflow.ProcessInstanceRequest batch =
                new org.egov.pgr.web.models.workflow.ProcessInstanceRequest(
                        request("ASSIGN", TOKEN).getRequestInfo(),
                        List.of(new org.egov.pgr.web.models.workflow.ProcessInstance(),
                                new org.egov.pgr.web.models.workflow.ProcessInstance()));

        CustomException thrown = assertThrows(CustomException.class, () -> {
            java.lang.reflect.Method callWorkFlow =
                    WorkflowService.class.getDeclaredMethod("callWorkFlow",
                            org.egov.pgr.web.models.workflow.ProcessInstanceRequest.class);
            callWorkFlow.setAccessible(true);
            try {
                callWorkFlow.invoke(workflowService, batch);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (RuntimeException) e.getCause();
            }
        });

        assertEquals("WORKFLOW_TRANSITION_BATCH_UNSUPPORTED", thrown.getCode());
        server.verify();
    }
}
