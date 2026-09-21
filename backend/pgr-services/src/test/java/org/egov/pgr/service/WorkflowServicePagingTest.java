package org.egov.pgr.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.workflow.ProcessInstance;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * v3 pages every workflow list; 2.x pages none of them, and the 2.x envelope CCRS reads has no
 * paging field at all.
 *
 * <p>That asymmetry is the dangerous one, because the default is the trap: omit {@code size} and
 * v3 quietly gives 20 rows with HTTP 200, which for a complaint with a longer history looks
 * exactly like a complete answer. The hybrid shim could not page — one request in, one request out
 * — and settled for comparing v3's own {@code totalCount} to the rows that arrived and recording
 * the shortfall. CCRS is not a shim: it pages, and it still makes that comparison at the end, as
 * the assertion that the paging finished.
 *
 * <p>The two consequences of not doing this, both real: an assignee inbox silently loses every
 * complaint past the cap, and a history longer than the cap makes
 * {@code NotificationService.getEmployeeName} scan an incomplete history and resolve the wrong
 * assignee or none.
 */
class WorkflowServicePagingTest {

    private static final String TENANT = "ke.bomet";
    private static final String ACTOR = "employee-uuid";
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
        config.setWfProcessInstanceSearchPath("/egov-workflow-v2/egov-wf/process/_search");
        config.setWorkflowV3Host("http://localhost:8084");
        config.setWorkflowV3TransitionPath("/workflow/v3/transition");
        config.setWorkflowV3ProcessDefinitionSearchPath("/workflow/v3/process/definition");
        config.setV3PageMaxSize(100);
        config.setWorkflowV3MaxPages(50);
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_V3);

        workflowService = new WorkflowService(config, new ServiceRequestRepository(objectMapper, restTemplate), objectMapper);
    }

    private RequestInfo requestInfo() {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(User.builder().uuid(ACTOR).tenantId(TENANT)
                .roles(Collections.emptyList()).build());
        return requestInfo;
    }

    /** {@code count} transition rows, actions numbered so the ORDER across pages can be asserted. */
    private static String pageOf(int totalCount, int from, int count) {
        String rows = IntStream.range(from, from + count)
                .mapToObj(i -> "{\"id\":\"t" + i + "\",\"processCode\":\"PGR\",\"entityId\":\"" + COMPLAINT
                        + "\",\"action\":\"A" + i + "\",\"currentState\":\"PENDINGATLME\"}")
                .collect(Collectors.joining(","));
        return "{\"totalCount\":" + totalCount + ",\"page\":0,\"size\":100,\"processInstances\":[" + rows + "]}";
    }

    @Test
    @DisplayName("a 137-transition history is fetched in two pages and returned whole, in order")
    void aHistoryLongerThanOnePageIsPaged() {
        server.expect(requestTo(allOf(containsString("history=true"), containsString("size=100"), containsString("page=0"))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(pageOf(137, 0, 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo(allOf(containsString("history=true"), containsString("size=100"), containsString("page=1"))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(pageOf(137, 100, 37), MediaType.APPLICATION_JSON));

        List<ProcessInstance> history = workflowService.searchProcessInstanceHistory(requestInfo(), TENANT, COMPLAINT);
        server.verify();

        assertEquals(137, history.size());
        // Order matters: getEmployeeName takes the LAST matching action out of the history.
        assertEquals("A0", history.get(0).getAction());
        assertEquals("A136", history.get(136).getAction());
    }

    @Test
    @DisplayName("a result that fits in one page costs exactly one call, even when it fills it")
    void aFullButCompletePageIsNotFollowedByAnother() {
        server.expect(requestTo(containsString("page=0")))
                .andRespond(withSuccess(pageOf(100, 0, 100), MediaType.APPLICATION_JSON));

        assertEquals(100, workflowService.searchProcessInstanceHistory(requestInfo(), TENANT, COMPLAINT).size());
        server.verify();
    }

    @Test
    @DisplayName("hitting the page bound with work left is reported, not returned as a short list")
    void thePageBoundIsLoud() {
        config.setWorkflowV3MaxPages(1);
        server.expect(requestTo(containsString("page=0")))
                .andRespond(withSuccess(pageOf(250, 0, 100), MediaType.APPLICATION_JSON));

        CustomException thrown = assertThrows(CustomException.class,
                () -> workflowService.searchProcessInstanceHistory(requestInfo(), TENANT, COMPLAINT));

        assertEquals("WORKFLOW_RESULT_TRUNCATED", thrown.getCode());
        assertThat(thrown.getMessage(), allOf(containsString("250"), containsString("100")));
        server.verify();
    }

    @Test
    @DisplayName("a configured page size above v3's cap is clamped to 100 rather than answered 400")
    void thePageSizeIsClampedToWhatV3Accepts() {
        // v3 answers 400 "size must be an integer between 1 and 100" outside 1..100, and omitting
        // size silently gives 20 - so it is always sent, and always in range.
        config.setV3PageMaxSize(500);
        server.expect(requestTo(allOf(containsString("size=100"), not(containsString("size=500")))))
                .andRespond(withSuccess(pageOf(1, 0, 1), MediaType.APPLICATION_JSON));

        workflowService.searchProcessInstanceHistory(requestInfo(), TENANT, COMPLAINT);
        server.verify();
    }

    @Test
    @DisplayName("the 2.x call is not paged: no size, no page, one request, as before")
    void twoXIsNotPaged() {
        config.setWorkflowApiVersion(PGRConfiguration.API_VERSION_2X);
        server.expect(requestTo(allOf(
                        containsString("/egov-workflow-v2/egov-wf/process/_search"),
                        containsString("history=true"),
                        not(containsString("size=")),
                        not(containsString("page=")))))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ProcessInstances\":[{\"businessId\":\"" + COMPLAINT + "\",\"action\":\"APPLY\"}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals(1, workflowService.searchProcessInstanceHistory(requestInfo(), TENANT, COMPLAINT).size());
        server.verify();
    }

    @Test
    @DisplayName("the process definition search is NOT paged, because that endpoint ignores size")
    void theDefinitionSearchIsNotPaged() {
        // Probed live 2026-09-21: ?size=1 returned all three registered definitions and echoed
        // "size":3. Sending a page size there would be theatre; the totalCount check stays.
        server.expect(requestTo(allOf(
                        containsString("/workflow/v3/process/definition"),
                        not(containsString("size=")),
                        not(containsString("page=")))))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"definitions\":[{\"id\":\"d1\",\"code\":\"PGR\",\"states\":[]}],"
                        + "\"page\":0,\"size\":1,\"totalCount\":1}", MediaType.APPLICATION_JSON));

        assertEquals("PGR", workflowService.getBusinessService(requestInfo(), TENANT).getBusinessService());
        server.verify();
    }
}
