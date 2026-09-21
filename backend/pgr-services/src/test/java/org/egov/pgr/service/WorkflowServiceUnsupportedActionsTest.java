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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * COMMENT and ESCALATE under DIGIT 3.0 — the two actions v3 stores and refuses.
 *
 * <p>CCRS models both as self-loops ({@code nextState == currentState}). The v3 engine accepts
 * them at registration — the rows are in its database, COMMENT at five states and ESCALATE at
 * PENDINGATLME — and answers every attempt with
 * {@code 409 "Process instance is no longer in the expected state: invalid state transition: no
 * transition"}. Re-verified against the running stack on 2026-09-21 for both actions.
 *
 * <p><b>CCRS's decision is to fail loudly</b>, and these tests are where that decision is stated as
 * behaviour rather than as a comment. The two alternatives were degrading (accept the action and
 * quietly record nothing) and keeping the two actions on 2.x while the rest moves. The first is the
 * silent-success failure this whole migration exists to stop — a citizen told their comment was
 * saved when it was not. The second splits one complaint's state machine across two engines.
 *
 * <p>On 2.x, both actions keep working exactly as they always have. That is asserted too: this is
 * a v3-flag regression, not a feature deletion.
 */
class WorkflowServiceUnsupportedActionsTest {

    private static final String TENANT = "ke.bomet";
    private static final String COMPLAINT = "PG-PGR-2026-09-21-900044";

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
        config.setWorkflowV3Host("http://localhost:8084");
        config.setWorkflowV3TransitionPath("/workflow/v3/transition");
        config.setWorkflowV3ProcessDefinitionSearchPath("/workflow/v3/process/definition");
        config.setWorkflowV3UnsupportedActions("COMMENT,ESCALATE");
        config.setV3PageMaxSize(100);
        config.setWorkflowV3MaxPages(50);
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_2X);

        workflowService = new WorkflowService(config, new ServiceRequestRepository(objectMapper, restTemplate), objectMapper);
    }

    private void flipToV3() {
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_V3);
    }

    /** updateWorkflowStatus resolves the business service before it transitions. */
    private void expectDefinitionSearch() {
        server.expect(requestTo(startsWith("http://localhost:8084/workflow/v3/process/definition")))
                .andRespond(withSuccess("{\"definitions\":[{\"id\":\"d1\",\"code\":\"PGR\",\"states\":[]}],"
                        + "\"totalCount\":1}", MediaType.APPLICATION_JSON));
    }

    private ServiceRequest request(String action) {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setAuthToken("gateway-issued-token");
        requestInfo.setUserInfo(User.builder().uuid("employee-uuid").tenantId(TENANT)
                .roles(Collections.singletonList(Role.builder().code("PGR_LME").build())).build());

        return ServiceRequest.builder()
                .requestInfo(requestInfo)
                .service(Service.builder().tenantId(TENANT).serviceRequestId(COMPLAINT).build())
                .workflow(Workflow.builder().action(action).comments("a citizen comment").build())
                .build();
    }

    @Test
    @DisplayName("v3: COMMENT is refused at CCRS's own boundary, before anything reaches the wire")
    void v3RefusesComment() {
        flipToV3();
        // NOTHING is expected on the wire. Not the transition, and not even the business service
        // search that normally precedes it: asking v3 and being answered 409 would also work, but
        // it spends a round trip to reach the same refusal and makes the diagnosis depend on the
        // upstream wording.
        CustomException thrown = assertThrows(CustomException.class,
                () -> workflowService.updateWorkflowStatus(request("COMMENT")));

        assertEquals("WORKFLOW_ACTION_UNSUPPORTED_ON_V3", thrown.getCode());
        assertThat(thrown.getMessage(), containsString("COMMENT"));
        assertThat(thrown.getMessage(), containsString(COMPLAINT));
        // The message has to carry the way out, or an operator is left guessing.
        assertThat(thrown.getMessage(), containsString("egov.workflow.api.version=2.x"));
        server.verify();
    }

    @Test
    @DisplayName("v3: ESCALATE is refused the same way")
    void v3RefusesEscalate() {
        flipToV3();
        CustomException thrown = assertThrows(CustomException.class,
                () -> workflowService.updateWorkflowStatus(request("ESCALATE")));

        assertEquals("WORKFLOW_ACTION_UNSUPPORTED_ON_V3", thrown.getCode());
        assertThat(thrown.getMessage(), containsString("ESCALATE"));
        server.verify();
    }

    @Test
    @DisplayName("v3: the refusal is case-insensitive, so a lower-case action cannot slip past it")
    void v3RefusalIsCaseInsensitive() {
        flipToV3();
        assertEquals("WORKFLOW_ACTION_UNSUPPORTED_ON_V3",
                assertThrows(CustomException.class,
                        () -> workflowService.updateWorkflowStatus(request("comment"))).getCode());
        server.verify();
    }

    @Test
    @DisplayName("v3: a state-changing action is untouched by the refusal")
    void v3AllowsRealTransitions() {
        flipToV3();
        expectDefinitionSearch();
        server.expect(requestTo("http://localhost:8084/workflow/v3/transition"))
                .andRespond(withSuccess("{\"id\":\"t2\",\"entityId\":\"" + COMPLAINT + "\",\"action\":\"ASSIGN\","
                        + "\"currentState\":\"PENDINGATLME\"}", MediaType.APPLICATION_JSON));

        // A comment attached to a REAL transition still survives: v3 carries it as the transition's
        // `comment` string. Only the standalone comment has nowhere to live.
        assertEquals("PENDINGATLME", workflowService.updateWorkflowStatus(request("ASSIGN")));
        server.verify();
    }

    @Test
    @DisplayName("v3: an unanticipated self-loop 409 is diagnosed as the same thing, not as a bare transport failure")
    void v3TranslatesTheRuntime409() {
        flipToV3();
        expectDefinitionSearch();
        // Say a definition is edited later and gains a self-loop the configured list does not name.
        config.setWorkflowV3UnsupportedActions("");
        server.expect(requestTo("http://localhost:8084/workflow/v3/transition"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("[{\"code\":\"Conflict\",\"message\":\"Process instance is no longer in the expected "
                                + "state: invalid state transition: no transition\"}]"));

        CustomException thrown = assertThrows(CustomException.class,
                () -> workflowService.updateWorkflowStatus(request("COMMENT")));

        assertEquals("WORKFLOW_ACTION_UNSUPPORTED_ON_V3", thrown.getCode());
        // v3's own wording survives into the diagnosis; on this failure the message IS the finding.
        assertThat(thrown.getMessage(), containsString("invalid state transition"));
        server.verify();
    }

    @Test
    @DisplayName("v3: clearing the configured list adopts a build that supports self-loops, with no code change")
    void anEmptyListMeansNoRefusal() {
        flipToV3();
        expectDefinitionSearch();
        config.setWorkflowV3UnsupportedActions("");
        server.expect(requestTo("http://localhost:8084/workflow/v3/transition"))
                .andRespond(withSuccess("{\"id\":\"t2\",\"entityId\":\"" + COMPLAINT + "\",\"action\":\"COMMENT\","
                        + "\"currentState\":\"PENDINGATLME\"}", MediaType.APPLICATION_JSON));

        assertEquals("PENDINGATLME", workflowService.updateWorkflowStatus(request("COMMENT")));
        server.verify();
    }

    @Test
    @DisplayName("2.x: COMMENT still works, because this is a v3-flag regression and not a feature deletion")
    void twoXCommentStillWorks() {
        server.expect(requestTo("http://localhost:8084/egov-workflow-v2/egov-wf/businessservice/_search"
                        + "?tenantId=ke.bomet&businessServices=PGR"))
                .andRespond(withSuccess("{\"BusinessServices\":[{\"businessService\":\"PGR\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8084/egov-workflow-v2/egov-wf/process/_transition"))
                .andRespond(withSuccess("{\"ProcessInstances\":[{\"businessId\":\"" + COMPLAINT
                        + "\",\"action\":\"COMMENT\",\"state\":{\"applicationStatus\":\"PENDINGATLME\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals("PENDINGATLME", workflowService.updateWorkflowStatus(request("COMMENT")));
        server.verify();
    }

    @Test
    @DisplayName("2.x: ESCALATE still works too")
    void twoXEscalateStillWorks() {
        server.expect(requestTo(startsWith("http://localhost:8084/egov-workflow-v2/egov-wf/businessservice/_search")))
                .andRespond(withSuccess("{\"BusinessServices\":[{\"businessService\":\"PGR\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:8084/egov-workflow-v2/egov-wf/process/_transition"))
                .andRespond(withSuccess("{\"ProcessInstances\":[{\"businessId\":\"" + COMPLAINT
                        + "\",\"action\":\"ESCALATE\",\"state\":{\"applicationStatus\":\"PENDINGATLME\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals("PENDINGATLME", workflowService.updateWorkflowStatus(request("ESCALATE")));
        server.verify();
    }
}
