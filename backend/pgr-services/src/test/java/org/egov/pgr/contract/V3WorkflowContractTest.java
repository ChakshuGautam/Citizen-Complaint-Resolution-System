package org.egov.pgr.contract;

import org.egov.pgr.service.WorkflowService;
import org.egov.pgr.web.models.Service;
import org.egov.pgr.web.models.ServiceRequest;
import org.egov.pgr.web.models.ServiceWrapper;
import org.egov.pgr.web.models.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wire contract for the three workflow directions. This is the dependency with the deepest damage:
 * five call shapes behind two v3 endpoints, a mandatory processCode that 2.x never sent, a
 * hard page cap, and a transition body that renames or drops almost every field.
 */
class V3WorkflowContractTest extends V3ContractTestSupport {

    private static final String SERVICE_REQUEST_ID = "PG-PGR-2026-09-20-900032";

    /** The v3 process-definition reply, used wherever a transition needs the definition first. */
    private static final String V3_DEFINITION_RESPONSE =
            "{\"totalCount\":1,\"definitions\":[{\"id\":\"d1\",\"code\":\"PGR\",\"tenantId\":\"ke.bomet\",\"sla\":432000000,"
                    + "\"states\":[{\"id\":\"s1\",\"code\":\"START\",\"isInitial\":true},{\"id\":\"s2\",\"code\":\"PENDINGFORASSIGNMENT\"}]}]}";

    private WorkflowService workflowService;

    @BeforeEach
    void wireCaller() {
        workflowService = new WorkflowService(config, serviceRequestRepository, objectMapper);
    }

    private ServiceRequest transitionRequest() {
        return ServiceRequest.builder()
                .requestInfo(requestInfo())
                .service(Service.builder()
                        .tenantId(TENANT)
                        .serviceRequestId(SERVICE_REQUEST_ID)
                        .build())
                .workflow(Workflow.builder()
                        .action("ASSIGN")
                        .comments("assigning to the ward officer")
                        .assignes(Collections.singletonList("assignee-uuid"))
                        .build())
                .build();
    }

    @Test
    @Disabled("Enabled by C10 — feat(workflow): move the two read paths to v3")
    void businessServiceSearchIsAGetOnProcessCode() {
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8084/workflow/v3/process/definition?"),
                        containsString("processCode=PGR"),
                        // 2.x spells it businessServices and puts the tenant on the query; v3 does
                        // neither.
                        not(containsString("businessServices=")),
                        not(containsString("tenantId=")))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-ID", TENANT))
                // 2.x sends {"RequestInfo":{...}}; a v3 read takes no body at all.
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess(V3_DEFINITION_RESPONSE, MediaType.APPLICATION_JSON));

        assertingOnlyTheRequest(() -> workflowService.getBusinessService(transitionRequest()));
    }

    @Test
    @Disabled("Enabled by C10 — feat(workflow): move the two read paths to v3")
    void processInstanceSearchIsAGetCarryingTheMandatoryProcessCode() {
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8084/workflow/v3/transition?"),
                        containsString("entityId=" + SERVICE_REQUEST_ID),
                        // processCode is MANDATORY on v3 (400 without it). 2.x sends none, because
                        // business ids are globally unique there. Every CCRS call site has
                        // PGR_BUSINESSSERVICE in hand, so supply it explicitly rather than letting
                        // anything default it - a second module behind a default would be searched
                        // under the wrong process and answered empty with HTTP 200.
                        containsString("processCode=PGR"),
                        not(containsString("businessIds=")),
                        not(containsString("tenantId=")))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-ID", TENANT))
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess("{\"totalCount\":1,\"processInstances\":[{\"id\":\"t1\",\"processCode\":\"PGR\",\"entityId\":\""
                                + SERVICE_REQUEST_ID + "\",\"action\":\"APPLY\",\"currentState\":\"PENDINGFORASSIGNMENT\",\"assignees\":[],"
                                + "\"assigner\":\"\",\"comment\":null,\"documents\":null,\"auditDetail\":{\"createdBy\":\"sys\",\"createdTime\":1695000000000},"
                                + "\"processSla\":432000000,\"stateSla\":86400000,\"isLatest\":true,\"escalated\":false}]}",
                        MediaType.APPLICATION_JSON));

        ServiceWrapper wrapper = ServiceWrapper.builder()
                .service(Service.builder().tenantId(TENANT).serviceRequestId(SERVICE_REQUEST_ID).build())
                .build();

        assertingOnlyTheRequest(() -> workflowService.enrichWorkflow(requestInfo(), List.of(wrapper)));
    }

    @Test
    @Disabled("Enabled by C13 — feat(workflow): move the transition to POST /workflow/v3/transition (blocked on the identity work: roles must ride a real Keycloak bearer token)")
    void transitionIsAPostOfOneEntityWithRolesOnABearerToken() {
        // updateWorkflowStatus resolves the business service first, so the definition search comes
        // through before the transition does.
        server.expect(requestTo(startsWith("http://localhost:8084/workflow/v3/process/definition")))
                .andRespond(withSuccess(V3_DEFINITION_RESPONSE, MediaType.APPLICATION_JSON));

        server.expect(requestTo("http://localhost:8084/workflow/v3/transition"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Tenant-ID", TENANT))
                .andExpect(header("X-User-ID", ACTOR_UUID))
                // The workflow service reads action roles ONLY from
                // Authorization: Bearer <jwt>.realm_access.roles. An X-Roles header is answered 403.
                // This must be a token Keycloak issued - the shim forges an unsigned {"alg":"none"}
                // JWT because the deployed build does not verify signatures, and CCRS must not.
                .andExpect(header("Authorization", "Bearer " + AUTH_TOKEN))
                // One entity per call. v3 takes no list, no RequestInfo and no ProcessInstances
                // wrapper; assignees are bare uuid STRINGS, not User objects.
                .andExpect(content().json("{\"processCode\":\"PGR\",\"entityId\":\"" + SERVICE_REQUEST_ID
                        + "\",\"action\":\"ASSIGN\",\"comment\":\"assigning to the ward officer\","
                        + "\"assignees\":[\"assignee-uuid\"]}"))
                .andExpect(content().string(allOf(
                        not(containsString("ProcessInstances")),
                        not(containsString("RequestInfo")),
                        not(containsString("businessId")),
                        not(containsString("businessService")),
                        // 2.x overwrote CCRS's "PGR" with "pgr-services"; v3 has no such field.
                        not(containsString("moduleName")))))
                .andRespond(withSuccess("{\"id\":\"t2\",\"processCode\":\"PGR\",\"entityId\":\"" + SERVICE_REQUEST_ID
                                + "\",\"action\":\"ASSIGN\",\"currentState\":\"PENDINGATLME\",\"assignees\":[\"assignee-uuid\"],"
                                + "\"assigner\":\"\",\"comment\":\"assigning to the ward officer\",\"documents\":null,"
                                + "\"auditDetail\":{\"createdBy\":\"" + ACTOR_UUID + "\",\"createdTime\":1695000000000},"
                                + "\"processSla\":432000000,\"stateSla\":86400000,\"isLatest\":true,\"escalated\":false}",
                        MediaType.APPLICATION_JSON));

        assertingOnlyTheRequest(() -> workflowService.updateWorkflowStatus(transitionRequest()));
    }
}
