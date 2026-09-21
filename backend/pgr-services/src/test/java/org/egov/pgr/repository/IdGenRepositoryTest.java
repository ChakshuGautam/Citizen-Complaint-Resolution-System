package org.egov.pgr.repository;

import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.util.V3Headers;
import org.egov.pgr.web.models.Idgen.IdGenerationRequest;
import org.egov.pgr.web.models.Idgen.IdGenerationResponse;
import org.egov.pgr.web.models.Idgen.IdRequest;
import org.egov.pgr.web.models.Idgen.IdResponse;
import org.egov.tracer.model.CustomException;
import org.egov.tracer.model.ServiceCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Both idgen paths, and the error path that used to destroy its own evidence.
 *
 * <p>There was no test for this class at all before this commit, which is worth stating plainly:
 * the single most complaint-critical call in the service - the one that mints the id a citizen is
 * told to quote - was covered only by mocks of the repository in EnrichmentService's callers.
 */
class IdGenRepositoryTest {

    private static final String IDREQUEST_TENANT = "ke.bomet";
    private static final String ACTING_TENANT = "ke";
    private static final String TEMPLATE = "pgr.servicerequestid";
    private static final String FORMAT = "PB-PGR-[cy:yyyy-MM-dd]-[SEQ_EG_PGR_ID]";

    private RestTemplate restTemplate;
    private ServiceRequestRepository serviceRequestRepository;
    private PGRConfiguration config;
    private IdGenRepository idGenRepository;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        serviceRequestRepository = mock(ServiceRequestRepository.class);

        config = new PGRConfiguration();
        config.setIdGenHost("http://localhost:8083/");
        config.setIdGenPath("egov-idgen/id/_generate");
        config.setIdgenV3Host("http://localhost:8083/");
        config.setIdgenV3GeneratePath("/idgen/v3/generate");
        config.setIdgenV3GenerateBulkPath("/idgen/v3/generate/bulk");

        idGenRepository = new IdGenRepository(restTemplate, config, serviceRequestRepository);
    }

    /** A citizen registered at the state level filing against a city - the two tenants differ. */
    private RequestInfo requestInfo() {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(User.builder().uuid("citizen-uuid").tenantId(ACTING_TENANT).build());
        return requestInfo;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureV3Body() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(serviceRequestRepository).exchange(any(HttpMethod.class), anyString(), body.capture(),
                anyMap(), eq(Map.class));
        return (Map<String, Object>) body.getValue();
    }

    private String captureV3Uri() {
        ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
        verify(serviceRequestRepository).exchange(any(HttpMethod.class), uri.capture(), any(),
                anyMap(), eq(Map.class));
        return uri.getValue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, String> captureV3Headers() {
        ArgumentCaptor<Map> headers = ArgumentCaptor.forClass(Map.class);
        verify(serviceRequestRepository).exchange(any(HttpMethod.class), anyString(), any(),
                headers.capture(), eq(Map.class));
        return headers.getValue();
    }

    private void v3Answers(Map<String, Object> reply) {
        config.setIdgenApiVersion(PGRConfiguration.API_VERSION_V3);
        when(serviceRequestRepository.exchange(any(HttpMethod.class), anyString(), any(), anyMap(), eq(Map.class)))
                .thenReturn(reply);
    }

    // ------------------------------------------------------------------ 2.x, unchanged

    @Test
    void twoXStillPostsAnIdRequestListInsideARequestInfoEnvelope() {
        when(restTemplate.postForObject(anyString(), any(), eq(IdGenerationResponse.class)))
                .thenReturn(IdGenerationResponse.builder()
                        .idResponses(List.of(IdResponse.builder().id("PG-PGR-2026-09-21-000001").build()))
                        .build());

        IdGenerationResponse response = idGenRepository.getId(requestInfo(), IDREQUEST_TENANT, TEMPLATE, FORMAT, 1);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForObject(url.capture(), body.capture(), eq(IdGenerationResponse.class));

        assertEquals("http://localhost:8083/egov-idgen/id/_generate", url.getValue());
        IdGenerationRequest sent = (IdGenerationRequest) body.getValue();
        assertEquals(1, sent.getIdRequests().size());
        IdRequest idRequest = sent.getIdRequests().get(0);
        assertEquals(TEMPLATE, idRequest.getIdName());
        // 2.x carries the format and the tenant in the body; both must survive a v3 commit that
        // does not touch this branch.
        assertEquals(FORMAT, idRequest.getFormat());
        assertEquals(IDREQUEST_TENANT, idRequest.getTenantId());
        assertNotNull(sent.getRequestInfo());
        assertEquals("PG-PGR-2026-09-21-000001", response.getIdResponses().get(0).getId());
        verify(serviceRequestRepository, never()).exchange(any(), anyString(), any(), anyMap(), any());
    }

    // ------------------------------------------------------------------ v3

    @Test
    void v3PostsATemplateCodeAloneAndReadsTheSingularId() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("templateCode", TEMPLATE);
        reply.put("version", "v1");
        reply.put("id", "PG-PGR-2026-09-21-900044");
        v3Answers(reply);

        IdGenerationResponse response = idGenRepository.getId(requestInfo(), IDREQUEST_TENANT, TEMPLATE, FORMAT, 1);

        assertEquals("http://localhost:8083/idgen/v3/generate", captureV3Uri());
        // Exactly one key. v3 has no RequestInfo envelope, no idRequests[] and no per-request
        // format; a format sent here is accepted and discarded, which is the kind of silent
        // acceptance this migration exists to stop relying on.
        assertEquals(Map.of("templateCode", TEMPLATE), captureV3Body());
        assertEquals(1, response.getIdResponses().size());
        assertEquals("PG-PGR-2026-09-21-900044", response.getIdResponses().get(0).getId());
        // v3 returns no envelope at all, and nothing in CCRS reads one. Not synthesised.
        assertEquals(null, response.getResponseInfo());
        verify(restTemplate, never()).postForObject(anyString(), any(), eq(IdGenerationResponse.class));
    }

    @Test
    void v3MintsAgainstTheIdRequestTenantNotTheCallersOwn() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("id", "PG-PGR-2026-09-21-900045");
        v3Answers(reply);

        idGenRepository.getId(requestInfo(), IDREQUEST_TENANT, TEMPLATE, FORMAT, 1);

        // The two legitimately differ. It is the idRequest tenant whose template mints the id -
        // 2.x keyed both the MDMS format and the sequence on it - so picking the acting user's
        // tenant here would silently mint ids from the wrong template.
        assertEquals(IDREQUEST_TENANT, captureV3Headers().get(V3Headers.TENANT_ID));
        assertEquals("citizen-uuid", captureV3Headers().get(V3Headers.USER_ID));
    }

    @Test
    void v3FallsBackToTheActingTenantWhenTheIdRequestCarriesNone() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("id", "PG-PGR-2026-09-21-900046");
        v3Answers(reply);

        idGenRepository.getId(requestInfo(), null, TEMPLATE, FORMAT, 1);

        assertEquals(ACTING_TENANT, captureV3Headers().get(V3Headers.TENANT_ID));
    }

    @Test
    void v3TakesTheBulkPathAndTheIdsArrayForMoreThanOne() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("templateCode", TEMPLATE);
        reply.put("count", 2);
        reply.put("ids", List.of("PG-PGR-2026-09-21-900047", "PG-PGR-2026-09-21-900048"));
        v3Answers(reply);

        IdGenerationResponse response = idGenRepository.getId(requestInfo(), IDREQUEST_TENANT, TEMPLATE, FORMAT, 2);

        assertEquals("http://localhost:8083/idgen/v3/generate/bulk", captureV3Uri());
        assertEquals(Map.of("templateCode", TEMPLATE, "count", 2), captureV3Body());
        assertEquals(List.of("PG-PGR-2026-09-21-900047", "PG-PGR-2026-09-21-900048"),
                response.getIdResponses().stream().map(IdResponse::getId).toList());
    }

    @Test
    void v3AnAnswerWithNoIdsIsAnEmptyListSoTheCallerRaisesItsOwnError() {
        // Not null: EnrichmentService.getIdList does CollectionUtils.isEmpty(...) and raises
        // "IDGEN ERROR: No ids returned from idgen Service". A null here would be a
        // NullPointerException three frames from the cause instead.
        v3Answers(new LinkedHashMap<>(Map.of("templateCode", TEMPLATE, "version", "v1")));

        IdGenerationResponse response = idGenRepository.getId(requestInfo(), IDREQUEST_TENANT, TEMPLATE, FORMAT, 1);

        assertNotNull(response.getIdResponses());
        assertTrue(response.getIdResponses().isEmpty());
    }

    @Test
    void v3RefusesToAskForAnIdWithNoTemplateCode() {
        config.setIdgenApiVersion(PGRConfiguration.API_VERSION_V3);

        CustomException e = assertThrows(CustomException.class,
                () -> idGenRepository.getId(requestInfo(), IDREQUEST_TENANT, "  ", FORMAT, 1));

        assertEquals("IDGEN_TEMPLATE_CODE_MISSING", e.getCode());
        verify(serviceRequestRepository, never()).exchange(any(), anyString(), any(), anyMap(), any());
    }

    // ------------------------------------------------------------------ the error path

    @Test
    void aTransportFailureReachesTheCallerWithItsDiagnosisIntact() {
        // THE REGRESSION THIS COMMIT HAD TO FIX. The old catch block read
        // e.getCause().getClass().getName() with no guard. A ServiceCallException from exchange()
        // has NO cause, so the error path itself threw a NullPointerException and the real
        // diagnosis - for idgen, almost always the 404 "template not found" that says the template
        // was never registered - was destroyed on its way out.
        config.setIdgenApiVersion(PGRConfiguration.API_VERSION_V3);
        ServiceCallException failure = new ServiceCallException(
                "POST http://localhost:8083/idgen/v3/generate -> 404 NOT_FOUND NOT_FOUND: template not found");
        when(serviceRequestRepository.exchange(any(HttpMethod.class), anyString(), any(), anyMap(), eq(Map.class)))
                .thenThrow(failure);

        ServiceCallException thrown = assertThrows(ServiceCallException.class,
                () -> idGenRepository.getId(requestInfo(), IDREQUEST_TENANT, TEMPLATE, FORMAT, 1));

        assertSame(failure, thrown);
        // getMessage() is always null on this class; the text lives in getError().
        assertTrue(thrown.getError().contains("template not found"), thrown.getError());
    }

    @Test
    void aCauselessFailureOnTheTwoXPathNoLongerNullPointers() {
        // Same guard, reached from the other side: any RuntimeException without a cause used to
        // turn into an NPE here regardless of which stack raised it.
        when(restTemplate.postForObject(anyString(), any(), eq(IdGenerationResponse.class)))
                .thenThrow(new IllegalStateException("connection pool exhausted"));

        CustomException e = assertThrows(CustomException.class,
                () -> idGenRepository.getId(requestInfo(), IDREQUEST_TENANT, TEMPLATE, FORMAT, 1));

        assertEquals("connection pool exhausted", e.getErrors().get(IllegalStateException.class.getName()));
    }
}
