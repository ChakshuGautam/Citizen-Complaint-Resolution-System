package org.egov.pgr.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.Service;
import org.egov.pgr.web.models.ServiceWrapper;
import org.egov.tracer.model.CustomException;
import org.egov.tracer.model.ServiceCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The employee inbox with two or more complaints in it — the call shape DIGIT 3.0 cannot serve.
 *
 * <p>2.x joins every complaint in a search result to its workflow in ONE request, with a
 * comma-separated {@code businessIds}. v3's {@code GET /workflow/v3/transition} serves exactly one
 * {@code entityId}: measured live on 2026-09-21, the comma form answers
 * {@code {"totalCount":0,"processInstances":[]}} and the repeated form
 * {@code ?entityId=a&entityId=b} honours only the FIRST value. Both come back HTTP 200. An A/B of
 * the same box in the same minute with only the host changed gave 2.x two wrappers and v3 none.
 *
 * <p>This is also the failure the happy-path lifecycle could not see, because every lifecycle
 * search is scoped to one complaint. It took a purpose-built instrument to find it, and these are
 * the tests that stand in for that instrument from here on. The thing being asserted is not that
 * the fan-out works — it is that a short list can never come back quietly.
 */
class WorkflowServiceInboxFanOutTest {

    private static final String TENANT = "ke.bomet";
    private static final String ACTOR = "employee-uuid";

    private ServiceRequestRepository repository;
    private PGRConfiguration config;
    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        repository = mock(ServiceRequestRepository.class);

        config = new PGRConfiguration();
        config.setWfHost("http://localhost:8084");
        config.setWfProcessInstanceSearchPath("/egov-workflow-v2/egov-wf/process/_search");
        config.setWorkflowV3Host("http://localhost:8084");
        config.setWorkflowV3TransitionPath("/workflow/v3/transition");
        config.setV3PageMaxSize(100);
        config.setWorkflowV3MaxPages(50);
        config.setWorkflowV3FanoutThreads(4);
        config.setWorkflowV3FanoutTimeoutSeconds(30);
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_V3);

        workflowService = new WorkflowService(config, repository, objectMapper);
    }

    private static String id(int i) {
        return "PG-PGR-2026-09-21-9000" + i;
    }

    private RequestInfo requestInfo() {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(User.builder().uuid(ACTOR).tenantId(TENANT)
                .roles(Collections.emptyList()).build());
        return requestInfo;
    }

    private static List<ServiceWrapper> wrappers(List<String> ids) {
        return ids.stream()
                .map(id -> ServiceWrapper.builder()
                        .service(Service.builder().tenantId(TENANT).serviceRequestId(id).build())
                        .build())
                .collect(Collectors.toList());
    }

    /** One v3 search envelope holding the single latest transition for {@code entityId}. */
    private static Map<String, Object> v3Envelope(String entityId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "t-" + entityId);
        row.put("processCode", "PGR");
        row.put("entityId", entityId);
        row.put("action", "ASSIGN");
        row.put("currentState", "PENDINGATLME");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("totalCount", 1);
        envelope.put("processInstances", List.of(row));
        return envelope;
    }

    private static Map<String, Object> emptyV3Envelope() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("totalCount", 0);
        envelope.put("processInstances", List.of());
        return envelope;
    }

    /** Answers each GET from its own entityId, so a call for the wrong id cannot accidentally pass. */
    private void stubPerEntityId() {
        when(repository.exchange(eq(HttpMethod.GET), any(String.class), isNull(), anyMap(), eq(Map.class)))
                .thenAnswer(invocation -> {
                    String uri = invocation.getArgument(1);
                    String entityId = entityIdOf(uri);
                    return entityId == null ? emptyV3Envelope() : v3Envelope(entityId);
                });
    }

    private static String entityIdOf(String uri) {
        for (String param : uri.substring(uri.indexOf('?') + 1).split("&")) {
            if (param.startsWith("entityId="))
                return param.substring("entityId=".length());
        }
        return null;
    }

    private List<String> capturedUris() {
        ArgumentCaptor<String> uris = ArgumentCaptor.forClass(String.class);
        verify(repository, org.mockito.Mockito.atLeastOnce())
                .exchange(eq(HttpMethod.GET), uris.capture(), isNull(), anyMap(), eq(Map.class));
        return uris.getAllValues();
    }

    // ------------------------------------------------------------------ the test that matters

    @Test
    @DisplayName("TWO complaints: two single-entity calls, both enriched — never one CSV call, never a short list")
    void twoComplaintsAreJoinedByTwoCalls() {
        stubPerEntityId();

        List<ServiceWrapper> enriched = workflowService.enrichWorkflow(requestInfo(), wrappers(List.of(id(1), id(2))));

        assertEquals(2, enriched.size());
        assertEquals("ASSIGN", enriched.get(0).getWorkflow().getAction());
        assertEquals("ASSIGN", enriched.get(1).getWorkflow().getAction());

        List<String> uris = capturedUris();
        assertEquals(2, uris.size());
        assertEquals(List.of(id(1), id(2)), uris.stream().map(WorkflowServiceInboxFanOutTest::entityIdOf)
                .sorted().collect(Collectors.toList()));
        // The comma form is the one that answers totalCount 0 with HTTP 200. It is never sent.
        uris.forEach(uri -> assertFalse(uri.contains(","), "a comma-separated entityId was sent: " + uri));
        uris.forEach(uri -> assertFalse(uri.contains("businessIds="), "a 2.x businessIds was sent: " + uri));
        // The 2.x transport must not be touched at all on this path.
        verify(repository, never()).fetchResult(any(), any());
    }

    @Test
    @DisplayName("a 25-complaint inbox page is 25 calls, and every complaint comes back enriched")
    void afullInboxPageIsFannedOut() {
        stubPerEntityId();
        List<String> ids = IntStream.rangeClosed(1, 25).mapToObj(WorkflowServiceInboxFanOutTest::id)
                .collect(Collectors.toList());

        List<ServiceWrapper> enriched = workflowService.enrichWorkflow(requestInfo(), wrappers(ids));

        assertEquals(25, enriched.size());
        enriched.forEach(wrapper -> assertEquals("ASSIGN", wrapper.getWorkflow().getAction()));
        // 25 requests where 2.x made 1. That is the cost, and it is asserted rather than assumed.
        assertEquals(25, capturedUris().size());
    }

    @Test
    @DisplayName("one complaint with no workflow fails the whole join — it does not come back short")
    void oneMissingWorkflowFailsTheJoin() {
        when(repository.exchange(eq(HttpMethod.GET), any(String.class), isNull(), anyMap(), eq(Map.class)))
                .thenAnswer(invocation -> {
                    String entityId = entityIdOf(invocation.getArgument(1));
                    return id(2).equals(entityId) ? emptyV3Envelope() : v3Envelope(entityId);
                });

        CustomException thrown = assertThrows(CustomException.class,
                () -> workflowService.enrichWorkflow(requestInfo(), wrappers(List.of(id(1), id(2), id(3)))));

        // A page of three complaints where one silently lost its workflow is the exact shape this
        // migration exists to stop. Same error 2.x raises for the same condition.
        assertEquals("WORKFLOW_NOT_FOUND", thrown.getCode());
    }

    @Test
    @DisplayName("one call failing fails the join, and keeps the upstream diagnosis")
    void oneFailedCallFailsTheJoin() {
        when(repository.exchange(eq(HttpMethod.GET), any(String.class), isNull(), anyMap(), eq(Map.class)))
                .thenAnswer(invocation -> {
                    String entityId = entityIdOf(invocation.getArgument(1));
                    if (id(2).equals(entityId))
                        throw new ServiceCallException("[{\"code\":\"Conflict\",\"message\":\"boom\"}]");
                    return v3Envelope(entityId);
                });

        ServiceCallException thrown = assertThrows(ServiceCallException.class,
                () -> workflowService.enrichWorkflow(requestInfo(), wrappers(List.of(id(1), id(2)))));

        // Rethrown untouched: its text lives in getError() and getMessage() is always null there.
        assertTrue(thrown.getError().contains("boom"));
    }

    @Test
    @DisplayName("the gathered results keep the order the ids were asked in")
    void resultsKeepTheirOrder() {
        stubPerEntityId();
        List<String> ids = List.of(id(3), id(1), id(2));

        List<org.egov.pgr.web.models.workflow.ProcessInstance> out =
                workflowService.searchProcessInstances(requestInfo(), TENANT, ids);

        assertEquals(ids, out.stream()
                .map(org.egov.pgr.web.models.workflow.ProcessInstance::getBusinessId)
                .collect(Collectors.toList()));
    }

    @Test
    @DisplayName("a sequential fan-out (threads=1) does the same work, so the knob cannot change the answer")
    void aSequentialFanOutIsEquivalent() {
        config.setWorkflowV3FanoutThreads(1);
        stubPerEntityId();

        assertEquals(3, workflowService.enrichWorkflow(requestInfo(), wrappers(List.of(id(1), id(2), id(3)))).size());
        assertEquals(3, capturedUris().size());
    }

    @Test
    @DisplayName("2.x still joins the whole page in ONE call with a comma-separated businessIds")
    void twoXStillJoinsInOneCall() {
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_2X);

        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String serviceRequestId : List.of(id(1), id(2))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("businessId", serviceRequestId);
            row.put("action", "ASSIGN");
            rows.add(row);
        }
        response.put("ProcessInstances", rows);

        ArgumentCaptor<StringBuilder> url = ArgumentCaptor.forClass(StringBuilder.class);
        when(repository.fetchResult(url.capture(), any())).thenReturn(response);

        assertEquals(2, workflowService.enrichWorkflow(requestInfo(), wrappers(List.of(id(1), id(2)))).size());

        // The rollback path, pinned: one call, the CSV intact.
        verify(repository, times(1)).fetchResult(any(), any());
        assertTrue(url.getValue().toString().contains("businessIds=" + id(1) + "," + id(2)));
        verify(repository, never()).exchange(any(), any(String.class), any(), anyMap(), any());
    }
}
