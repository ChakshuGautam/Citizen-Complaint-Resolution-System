package org.egov.pgr.contract;

import org.egov.pgr.service.WorkflowService;
import org.egov.pgr.web.models.workflow.BusinessService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        // Also assert the PARSED result, not only the request. C3 wrote this test to pin the
        // request alone, because the response half belonged to the commit that migrated the
        // dependency - this is that commit. A request-only assertion would pass against a mapping
        // that read "definitions" as nothing: the envelope key, "id" -> "uuid", "code" ->
        // "businessService" and "sla" -> "businessServiceSla" are all renames CCRS's
        // case-insensitive ObjectMapper cannot absorb, and getting any of them wrong produces a
        // BusinessService full of nulls, not an error.
        BusinessService businessService = workflowService.getBusinessService(transitionRequest());
        server.verify();

        assertEquals("PGR", businessService.getBusinessService());
        assertEquals("d1", businessService.getUuid());
        assertEquals(432000000L, businessService.getBusinessServiceSla());
        assertEquals(2, businessService.getStates().size());
        // v3 forbids an empty state code and calls the 2.x nameless start state START; reading it
        // back has to undo that, because MigrationUtils keys a map on the 2.x spelling.
        assertNull(businessService.getStates().get(0).getState());
        assertEquals(Boolean.TRUE, businessService.getStates().get(0).getIsStartState());
        assertEquals("PENDINGFORASSIGNMENT", businessService.getStates().get(1).getState());
    }

    @Test
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
                // Not in the C3 sketch, and mandatory: probed live 2026-09-21, this endpoint
                // answers 400 "X-User-Id header is required" without it, although the process
                // definition search next door does not need it.
                .andExpect(header("X-User-ID", ACTOR_UUID))
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess("{\"totalCount\":1,\"processInstances\":[{\"id\":\"t1\",\"processCode\":\"PGR\",\"entityId\":\""
                                + SERVICE_REQUEST_ID + "\",\"action\":\"APPLY\",\"currentState\":\"PENDINGFORASSIGNMENT\",\"assignees\":[],"
                                + "\"assigner\":\"\",\"comment\":null,\"documents\":null,\"auditDetail\":{\"createdBy\":\"sys\",\"createdTime\":1695000000000},"
                                + "\"processSla\":432000000,\"stateSla\":86400000,\"isLatest\":true,\"escalated\":false}]}",
                        MediaType.APPLICATION_JSON));

        ServiceWrapper wrapper = ServiceWrapper.builder()
                .service(Service.builder().tenantId(TENANT).serviceRequestId(SERVICE_REQUEST_ID).build())
                .build();

        List<ServiceWrapper> enriched = workflowService.enrichWorkflow(requestInfo(), List.of(wrapper));
        server.verify();

        // And the parsed outcome. enrichWorkflow reads entityId -> businessId to key the join and
        // would otherwise hand every complaint a null workflow at HTTP 200.
        assertEquals(1, enriched.size());
        assertEquals("APPLY", enriched.get(0).getWorkflow().getAction());
    }

    @Test
    @Disabled("STILL GATED after C13. The transition itself IS on POST /workflow/v3/transition now, "
            + "and its produced request is asserted by WorkflowServiceTransitionTest. What this test "
            + "additionally asserts is a KEYCLOAK-ISSUED bearer token, and CCRS's gateway issues an "
            + "opaque 2.x token. Measured against the live v3 stack on 2026-09-21: an opaque uuid "
            + "bearer -> 403 'caller role does not permit the requested action'; a JWT with no "
            + "realm_access.roles -> the same 403; only a JWT carrying realm_access.roles -> 200. "
            + "CCRS will not mint one - V3Headers never forges a token, and the shim's unsigned "
            + "{alg:none} JWT is deliberately not ported, because a v3 build that verifies signatures "
            + "kills it instantly. MISSING: the identity work (W25) - a Keycloak realm issuing tokens "
            + "to CCRS whose realm_access.roles carry the caller's PGR roles, or a non-JWT role "
            + "channel for trusted internal callers. Enabling this test today would mean asserting a "
            + "token CCRS does not have.")
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
