package org.egov.pgr.validator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.PGRRepository;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.util.HRMSUtil;
import org.egov.pgr.util.MDMSUtils;
import org.egov.pgr.util.V3Headers;
import org.egov.pgr.web.models.*;
import org.egov.pgr.web.models.boundary.BoundaryResponse;
import org.egov.tracer.model.CustomException;
import org.egov.tracer.model.ServiceCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ServiceRequestValidatorTest {

    @Mock private PGRConfiguration config;
    @Mock private PGRRepository repository;
    @Mock private HRMSUtil hrmsUtil;
    @Mock private ServiceRequestRepository serviceRequestRepository;
    /**
     * A REAL mapper, configured exactly as PGRApp configures the production one.
     *
     * <p>It used to be a @Mock, with every boundary test stubbing
     * {@code convertValue(any(), eq(BoundaryResponse.class))} to hand back a ready-made
     * BoundaryResponse. That made these tests structurally incapable of seeing a response-KEY
     * change: they would have stayed green against a v3 reply CCRS could not read, which is
     * precisely the failure mode this migration exists to catch. They now stub the raw envelope
     * the transport actually returns and let the real mapper bind it.
     */
    @Spy private ObjectMapper objectMapper = new ObjectMapper()
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    @Mock private MDMSUtils mdmsUtils;

    @InjectMocks
    private ServiceRequestValidator validator;

    private ServiceRequest request;
    private Object mdmsData;

    @BeforeEach
    void setup() {
        request = buildRequest("LOC001", "POTHOLE");
        mdmsData = buildMdmsData("POTHOLE");
        when(config.getAllowedSource()).thenReturn("web");
        when(config.getIsValidateDeptEnabled()).thenReturn(false);
    }

    // ── validateBoundary ──────────────────────────────────────────────────────

    @Test
    void create_validBoundaryCode_passes() {
        stubBoundaryResponse("LOC001");
        assertDoesNotThrow(() -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_nullAddress_throwsInvalidBoundary() {
        request.getService().setAddress(null);
        assertCode("INVALID_BOUNDARY", () -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_nullLocality_throwsInvalidBoundary() {
        request.getService().getAddress().setLocality(null);
        assertCode("INVALID_BOUNDARY", () -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_nullLocalityCode_throwsInvalidBoundary() {
        request.getService().getAddress().getLocality().setCode(null);
        assertCode("INVALID_BOUNDARY", () -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_localityCodeNotReturnedByBoundaryService_throwsInvalidBoundaryCode() {
        stubBoundaryResponse("DIFFERENT_CODE");
        assertCode("INVALID_BOUNDARY_CODE", () -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_emptyBoundaryList_throwsInvalidBoundaryCode() {
        when(serviceRequestRepository.fetchResult(any(), any())).thenReturn(twoXEnvelope());
        assertCode("INVALID_BOUNDARY_CODE", () -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_twoXStillPostsTheLegacyUrlWithTheTenantOnTheQueryString() {
        // Nothing asserted the produced 2.x request before this commit. It does now, so the
        // rollback path cannot rot unnoticed while the v3 path is the one being exercised.
        when(config.getBoundaryHost()).thenReturn("http://localhost:8081");
        when(config.getBoundarySearchEndpoint()).thenReturn("/boundary-service/boundary/_search");
        stubBoundaryResponse("LOC001");

        assertDoesNotThrow(() -> validator.validateCreate(request, mdmsData));

        ArgumentCaptor<StringBuilder> uri = ArgumentCaptor.forClass(StringBuilder.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(serviceRequestRepository).fetchResult(uri.capture(), body.capture());
        assertEquals("http://localhost:8081/boundary-service/boundary/_search"
                + "?limit=10&offset=0&tenantId=pg.citya&codes=LOC001", uri.getValue().toString());
        // 2.x posts a BARE RequestInfo, not a {"RequestInfo":{...}} wrapper.
        assertSame(request.getRequestInfo(), body.getValue());
        verify(serviceRequestRepository, never()).exchange(any(), anyString(), any(), anyMap(), any());
    }

    // ── validateBoundary on v3 ────────────────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void create_v3IsAGetOnCodesWithNoBodyAndTheTenantInAHeader() {
        stubV3BoundaryResponse(v3Envelope("LOC001"));

        assertDoesNotThrow(() -> validator.validateCreate(request, mdmsData));

        ArgumentCaptor<HttpMethod> method = ArgumentCaptor.forClass(HttpMethod.class);
        ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Map> headers = ArgumentCaptor.forClass(Map.class);
        verify(serviceRequestRepository).exchange(method.capture(), uri.capture(), body.capture(),
                headers.capture(), eq(Map.class));

        assertEquals(HttpMethod.GET, method.getValue());
        assertEquals("http://localhost:8081/boundary/v3/boundaries?codes=LOC001", uri.getValue());
        assertNull(body.getValue(), "a v3 read carries no RequestInfo; a body makes it malformed");
        assertEquals("pg.citya", headers.getValue().get(V3Headers.TENANT_ID));
        verify(serviceRequestRepository, never()).fetchResult(any(), any());
    }

    @Test
    void create_v3LowercaseEnvelopeKeyBindsAndThePresentCodePasses() {
        // The lowercase "boundary" key binds to @JsonProperty("Boundary") for free, because
        // PGRApp turns on ACCEPT_CASE_INSENSITIVE_PROPERTIES. This test exists to pin that: it is
        // the reason this direction needs no key handling while the relationship search does.
        stubV3BoundaryResponse(v3Envelope("LOC001"));
        assertDoesNotThrow(() -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_v3CodeNotReturned_throwsInvalidBoundaryCode() {
        stubV3BoundaryResponse(v3Envelope("DIFFERENT_CODE"));
        assertCode("INVALID_BOUNDARY_CODE", () -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_v3EmptyBoundaryList_throwsInvalidBoundaryCode() {
        // A miss on v3 is 200 with {"boundary":[]} - the same shape as a 2.x miss, and a
        // legitimate answer. It must reach INVALID_BOUNDARY_CODE, never be faked into a hit.
        stubV3BoundaryResponse(v3Envelope());
        assertCode("INVALID_BOUNDARY_CODE", () -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_v3EnvelopeWithAnUnknownKeyIsRejectedRatherThanBindingToNulls() {
        // THE test the inventory says was missing. FAIL_ON_UNKNOWN_PROPERTIES is off and binding
        // is case-insensitive, so a renamed envelope key produces a well-formed BoundaryResponse
        // with a null list and throws nothing. The only acceptable outcome is a refusal: a
        // boundary that cannot be confirmed must not be accepted.
        Map<String, Object> renamed = new LinkedHashMap<>();
        renamed.put("boundaries", v3Envelope("LOC001").get("boundary"));
        stubV3BoundaryResponse(renamed);

        assertCode("INVALID_BOUNDARY_CODE", () -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_v3TransportFailure_throwsBoundaryServiceError() {
        when(config.isBoundaryV3()).thenReturn(true);
        when(config.getBoundaryV3Host()).thenReturn("http://localhost:8081");
        when(config.getBoundaryV3SearchEndpoint()).thenReturn("/boundary/v3/boundaries");
        when(serviceRequestRepository.exchange(any(HttpMethod.class), anyString(), any(), anyMap(), eq(Map.class)))
                .thenThrow(new ServiceCallException("GET .../boundary/v3/boundaries -> 400 BAD_REQUEST"));

        assertCode("BOUNDARY_SERVICE_SEARCH_ERROR", () -> validator.validateCreate(request, mdmsData));
    }

    @Test
    void create_boundaryServiceThrowsRuntimeException_throwsBoundaryServiceError() {
        when(serviceRequestRepository.fetchResult(any(), any())).thenThrow(new RuntimeException("connection refused"));
        assertCode("BOUNDARY_SERVICE_SEARCH_ERROR", () -> validator.validateCreate(request, mdmsData));
    }

    // ── validateMDMS ──────────────────────────────────────────────────────────

    @Test
    void create_serviceCodeNotInMDMS_throwsInvalidServiceCode() {
        stubBoundaryResponse("LOC001");
        assertCode("INVALID_SERVICECODE", () -> validator.validateCreate(request, buildMdmsData("GARBAGE")));
    }

    @Test
    void create_validServiceCode_passes() {
        stubBoundaryResponse("LOC001");
        assertDoesNotThrow(() -> validator.validateCreate(request, buildMdmsData("POTHOLE")));
    }

    // ── validateMDMS on update ────────────────────────────────────────────────

    @Test
    void update_serviceCodeNotInMDMS_throwsInvalidServiceCode() {
        assertCode("INVALID_SERVICECODE", () -> validator.validateUpdate(request, buildMdmsData("GARBAGE")));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The RAW 2.x envelope - capital "Boundary", as boundary-service really answers it. */
    private void stubBoundaryResponse(String code) {
        when(serviceRequestRepository.fetchResult(any(), any())).thenReturn(twoXEnvelope(code));
    }

    private Map<String, Object> twoXEnvelope(String... codes) {
        List<Map<String, Object>> boundaries = new ArrayList<>();
        for (String code : codes)
            boundaries.add(new LinkedHashMap<>(Map.of("id", "b-" + code, "tenantId", "pg.citya", "code", code)));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ResponseInfo", new LinkedHashMap<String, Object>());
        envelope.put("Boundary", boundaries);
        return envelope;
    }

    /**
     * The RAW v3 envelope, as probed live: lowercase "boundary", "additionalAttributes" rather
     * than "additionalDetails", and auditDetails spelled modifiedBy/modifiedTime.
     */
    private Map<String, Object> v3Envelope(String... codes) {
        List<Map<String, Object>> boundaries = new ArrayList<>();
        for (String code : codes) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("createdBy", "sys");
            audit.put("modifiedBy", "sys");
            audit.put("createdTime", 1695000000000L);
            audit.put("modifiedTime", 1695000000000L);
            Map<String, Object> boundary = new LinkedHashMap<>();
            boundary.put("id", "b-" + code);
            boundary.put("tenantId", "pg.citya");
            boundary.put("code", code);
            boundary.put("additionalAttributes", new LinkedHashMap<String, Object>());
            boundary.put("auditDetails", audit);
            boundaries.add(boundary);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("boundary", boundaries);
        return envelope;
    }

    private void stubV3BoundaryResponse(Map<String, Object> envelope) {
        when(config.isBoundaryV3()).thenReturn(true);
        when(config.getBoundaryV3Host()).thenReturn("http://localhost:8081");
        when(config.getBoundaryV3SearchEndpoint()).thenReturn("/boundary/v3/boundaries");
        when(serviceRequestRepository.exchange(any(HttpMethod.class), anyString(), any(), anyMap(), eq(Map.class)))
                .thenReturn(envelope);
    }

    private static void assertCode(String expectedCode, org.junit.jupiter.api.function.Executable block) {
        CustomException ex = assertThrows(CustomException.class, block);
        assertEquals(expectedCode, ex.getCode());
    }

    private static ServiceRequest buildRequest(String localityCode, String serviceCode) {
        org.egov.common.contract.request.User actor = org.egov.common.contract.request.User.builder()
                .uuid("citizen-uuid")
                .type("CITIZEN")
                .tenantId("pg.citya")
                .build();

        org.egov.common.contract.request.RequestInfo requestInfo =
                new org.egov.common.contract.request.RequestInfo();
        requestInfo.setUserInfo(actor);

        Address address = Address.builder()
                .tenantId("pg.citya")
                .locality(Boundary.builder().code(localityCode).build())
                .build();

        Service service = Service.builder()
                .id(UUID.randomUUID().toString())
                .tenantId("pg.citya")
                .serviceCode(serviceCode)
                .source("web")
                .address(address)
                .build();

        return ServiceRequest.builder()
                .requestInfo(requestInfo)
                .service(service)
                .workflow(Workflow.builder().action("APPLY").assignes(Collections.emptyList()).build())
                .build();
    }

    private static Object buildMdmsData(String serviceCode) {
        // Leaf row in the merged ComplaintHierarchy master: code == serviceCode, carries department.
        Map<String, Object> leaf = new HashMap<>();
        leaf.put("code", serviceCode);
        leaf.put("levelCode", "SUB_TYPE");
        leaf.put("name", serviceCode);
        leaf.put("department", "ROADS");

        Map<String, Object> rainmaker = new HashMap<>();
        rainmaker.put("ComplaintHierarchy", Collections.singletonList(leaf));

        Map<String, Object> mdmsRes = new HashMap<>();
        mdmsRes.put("RAINMAKER-PGR", rainmaker);

        Map<String, Object> root = new HashMap<>();
        root.put("MdmsRes", mdmsRes);
        return root;
    }

    // ── validateReOpen: window source and anti-forgery (#925, #1252) ───────────

    private static final long WINDOW_MS = 6 * 60 * 60 * 1000L;   // MDMS REOPENSLA
    // Deliberately NOT the shipped pgr.complain.idle.time default (259200000) — an arbitrary,
    // clearly-wider value, so a test that passes can only mean the MDMS window was used.
    private static final long PROPERTY_MS = 10L * 24 * 60 * 60 * 1000L;

    @Test
    void reopen_withinMdmsWindow_passes() {
        ServiceRequest req = reopenRequest();
        stubPersisted(req, "citizen-uuid", System.currentTimeMillis() - WINDOW_MS / 2);
        stubWindow(WINDOW_MS);
        assertDoesNotThrow(() -> validator.validateUpdate(req, buildMdmsData("POTHOLE")));
    }

    @Test
    void reopen_pastMdmsWindow_throwsInvalidAction() {
        ServiceRequest req = reopenRequest();
        stubPersisted(req, "citizen-uuid", System.currentTimeMillis() - WINDOW_MS * 2);
        stubWindow(WINDOW_MS);
        assertCode("INVALID_ACTION", () -> validator.validateUpdate(req, buildMdmsData("POTHOLE")));
    }

    /**
     * The heart of #925: the deadline must be read from the DB row, not the request body.
     * Here the caller forges a brand-new lastModifiedTime while the stored complaint is long
     * past the window — the reopen must still be refused.
     */
    @Test
    void reopen_forgedFreshLastModifiedTimeInRequestBody_stillBlocked() {
        ServiceRequest req = reopenRequest();   // body claims lastModifiedTime = now
        stubPersisted(req, "citizen-uuid", System.currentTimeMillis() - WINDOW_MS * 2);
        stubWindow(WINDOW_MS);
        assertCode("INVALID_ACTION", () -> validator.validateUpdate(req, buildMdmsData("POTHOLE")));
    }

    /**
     * Same anti-forgery guarantee for the ownership check: a citizen cannot reopen someone
     * else's complaint by putting their own uuid in the request body's accountId.
     */
    @Test
    void reopen_forgedAccountIdInRequestBody_stillBlocked() {
        ServiceRequest req = reopenRequest();   // body claims accountId = the caller
        stubPersisted(req, "a-different-citizen", System.currentTimeMillis());
        stubWindow(WINDOW_MS);
        assertCode("INVALID_ACTION", () -> validator.validateUpdate(req, buildMdmsData("POTHOLE")));
    }

    /**
     * #1252: the enforced window is MDMS REOPENSLA, not pgr.complain.idle.time. With the
     * property set far wider than the MDMS window, a complaint outside the MDMS window must
     * still be refused — otherwise the property is silently back in charge.
     */
    @Test
    void reopen_mdmsWindowWinsOverProperty() {
        ServiceRequest req = reopenRequest();
        stubPersisted(req, "citizen-uuid", System.currentTimeMillis() - WINDOW_MS * 2);
        when(mdmsUtils.getReopenWindowMillis(any(), any())).thenReturn(WINDOW_MS);
        when(config.getComplainMaxIdleTime()).thenReturn(PROPERTY_MS);   // 10 days, far wider
        assertCode("INVALID_ACTION", () -> validator.validateUpdate(req, buildMdmsData("POTHOLE")));
    }

    /** Fail closed: a persisted record with no audit details must not be reopenable. */
    @Test
    void reopen_persistedRecordWithoutAuditDetails_throwsInvalidAction() {
        ServiceRequest req = reopenRequest();
        Service persisted = Service.builder().tenantId("pg.citya").accountId("citizen-uuid").build();
        when(repository.getServiceWrappers(any()))
                .thenReturn(Collections.singletonList(ServiceWrapper.builder().service(persisted).build()));
        stubWindow(WINDOW_MS);
        assertCode("INVALID_ACTION", () -> validator.validateUpdate(req, buildMdmsData("POTHOLE")));
    }

    /** An employee is not subject to the citizen ownership check, but is subject to the window. */
    @Test
    void reopen_employeePastWindow_throwsInvalidAction() {
        ServiceRequest req = reopenRequest();
        req.getRequestInfo().getUserInfo().setType("EMPLOYEE");
        req.getRequestInfo().getUserInfo().setUuid("employee-uuid");
        stubPersisted(req, "citizen-uuid", System.currentTimeMillis() - WINDOW_MS * 2);
        stubWindow(WINDOW_MS);
        assertCode("INVALID_ACTION", () -> validator.validateUpdate(req, buildMdmsData("POTHOLE")));
    }

    /** A non-REOPEN update must not consult the reopen window at all. */
    @Test
    void nonReopenAction_ignoresReopenWindow() {
        ServiceRequest req = reopenRequest();
        req.getWorkflow().setAction("RESOLVE");
        stubPersisted(req, "someone-else", 0L);   // would fail every reopen check
        assertDoesNotThrow(() -> validator.validateUpdate(req, buildMdmsData("POTHOLE")));
    }

    // ── reopen helpers ────────────────────────────────────────────────────────

    private static ServiceRequest reopenRequest() {
        ServiceRequest req = buildRequest("LOC001", "POTHOLE");
        req.getWorkflow().setAction("REOPEN");
        // A well-formed body from the owning citizen: fresh timestamp + matching accountId.
        // Both are client-controlled, so every assertion below must come from the DB row
        // instead. Setting them here also keeps each test isolated: without them the
        // ownership check would throw first and mask whether the deadline check ever ran.
        req.getService().setAccountId("citizen-uuid");
        req.getService().setAuditDetails(
                AuditDetails.builder().lastModifiedTime(System.currentTimeMillis()).build());
        return req;
    }

    /** Stubs the DB row the validator is required to read instead of the request body. */
    private void stubPersisted(ServiceRequest req, String accountId, long lastModifiedTime) {
        Service persisted = Service.builder()
                .id(req.getService().getId())
                .tenantId("pg.citya")
                .accountId(accountId)
                .auditDetails(AuditDetails.builder().lastModifiedTime(lastModifiedTime).build())
                .build();
        when(repository.getServiceWrappers(any()))
                .thenReturn(Collections.singletonList(ServiceWrapper.builder().service(persisted).build()));
    }

    private void stubWindow(long millis) {
        when(mdmsUtils.getReopenWindowMillis(any(), any())).thenReturn(millis);
    }
}
