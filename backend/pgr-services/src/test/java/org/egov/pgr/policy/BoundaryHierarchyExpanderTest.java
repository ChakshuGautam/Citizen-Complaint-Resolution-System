package org.egov.pgr.policy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.util.V3Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link BoundaryHierarchyExpander} flattens boundary-service's nested
 * {@code boundary-relationships/_search} response into a flat descendant set, caches successful
 * resolutions, and falls back to the single unexpanded code — never a thrown exception — on any
 * lookup failure, since that fallback is strictly more restrictive than a successful expansion
 * (see the class Javadoc for why this differs from {@link AccessPolicyRegistry}'s fail-closed
 * outage handling).
 */
@ExtendWith(MockitoExtension.class)
class BoundaryHierarchyExpanderTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ServiceRequestRepository serviceRequestRepository;

    private PGRConfiguration config;
    private BoundaryHierarchyExpander expander;

    @BeforeEach
    void setup() {
        config = new PGRConfiguration();
        config.setBoundaryHost("http://localhost:8081/");
        config.setBoundaryRelationshipSearchEndpoint("/boundary-service/boundary-relationships/_search");
        config.setBoundaryV3Host("http://localhost:8081");
        config.setBoundaryV3RelationshipSearchEndpoint("/boundary/v3/relationship");
        // Configured as PGRApp configures the production mapper, because that configuration is
        // precisely what makes an unreadable v3 envelope silent.
        ObjectMapper mapper = new ObjectMapper()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        expander = new BoundaryHierarchyExpander(config, restTemplate, mapper, serviceRequestRepository);
    }

    private static RequestInfo employeeIn(String tenantId) {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(User.builder().uuid("employee-uuid").tenantId(tenantId).build());
        return requestInfo;
    }

    /**
     * The v3 envelope, as probed live: lowercase "tenantBoundary", auditDetails on every node
     * where 2.x emits none, and a LEAF that OMITS "children" where 2.x emits [].
     */
    private static Map<String, Object> v3NestedBoundaryResponse() {
        Map<String, Object> audit = Map.of("createdBy", "sys", "modifiedBy", "sys",
                "createdTime", 1695000000000L, "modifiedTime", 1695000000000L);
        Map<String, Object> ward1 = Map.of("id", "w1", "code", "BOMET_BOMET_CENTRAL_CHESOEN",
                "boundaryType", "Ward", "auditDetails", audit);
        Map<String, Object> ward2 = Map.of("id", "w2", "code", "BOMET_BOMET_CENTRAL_MUTARAKWA",
                "boundaryType", "Ward", "auditDetails", audit);
        Map<String, Object> subCounty = Map.of("id", "s1", "code", "BOMET_BOMET_CENTRAL",
                "boundaryType", "SubCounty", "auditDetails", audit, "children", List.of(ward1, ward2));
        Map<String, Object> county = Map.of("id", "c1", "code", "BOMET", "boundaryType", "County",
                "auditDetails", audit, "children", List.of(subCounty));
        Map<String, Object> tenantBoundary = Map.of("tenantId", "ke.bomet", "hierarchyType", "ADMIN",
                "boundary", List.of(county));
        return Map.of("tenantBoundary", List.of(tenantBoundary));
    }

    @SafeVarargs
    private void onV3(Map<String, Object>... replies) {
        config.setBoundaryApiVersion(PGRConfiguration.API_VERSION_V3);
        var stub = when(serviceRequestRepository.exchange(any(HttpMethod.class), anyString(), any(),
                anyMap(), eq(Map.class))).thenReturn(replies[0]);
        for (int i = 1; i < replies.length; i++)
            stub = stub.thenReturn(replies[i]);
    }

    private static Map<String, Object> nestedBoundaryResponse() {
        Map<String, Object> ward1 = Map.of("code", "BOMET_BOMET_CENTRAL_CHESOEN", "children", List.of());
        Map<String, Object> ward2 = Map.of("code", "BOMET_BOMET_CENTRAL_MUTARAKWA", "children", List.of());
        Map<String, Object> subCounty = Map.of("code", "BOMET_BOMET_CENTRAL", "children", List.of(ward1, ward2));
        Map<String, Object> county = Map.of("code", "BOMET", "children", List.of(subCounty));
        Map<String, Object> tenantBoundary = Map.of("boundary", List.of(county));
        return Map.of("TenantBoundary", List.of(tenantBoundary));
    }

    @Test
    void flattensTheNestedChildrenIntoAFlatDescendantSet() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(nestedBoundaryResponse());

        Set<String> codes = expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", "BOMET");

        assertEquals(Set.of("BOMET", "BOMET_BOMET_CENTRAL", "BOMET_BOMET_CENTRAL_CHESOEN", "BOMET_BOMET_CENTRAL_MUTARAKWA"), codes);
    }

    @Test
    void sendsTheExpectedBoundaryRelationshipSearchUrl() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(nestedBoundaryResponse());

        expander.descendantsOf(new RequestInfo(), "ke.bomet", "ADMIN", "BOMET");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).postForObject(url.capture(), any(), eq(Map.class));
        assertEquals(
                "http://localhost:8081/boundary-service/boundary-relationships/_search"
                        + "?tenantId=ke.bomet&hierarchyType=ADMIN&codes=BOMET&includeChildren=true",
                url.getValue());
    }

    @Test
    void cachesASuccessfulResolutionAndDoesNotRefetch() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(nestedBoundaryResponse());

        expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", "BOMET");
        expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", "BOMET");

        verify(restTemplate, times(1)).postForObject(anyString(), any(), eq(Map.class));
    }

    @Test
    void fallsBackToTheSingleUnexpandedCodeWhenTheLookupThrows() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenThrow(new RuntimeException("boundary-service unavailable"));

        Set<String> codes = expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", "BOMET");

        assertEquals(Set.of("BOMET"), codes);
    }

    @Test
    void aFailedLookupIsNotCached() {
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("boundary-service unavailable"))
                .thenReturn(nestedBoundaryResponse());

        Set<String> first = expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", "BOMET");
        Set<String> second = expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", "BOMET");

        assertEquals(Set.of("BOMET"), first);
        assertEquals(
                Set.of("BOMET", "BOMET_BOMET_CENTRAL", "BOMET_BOMET_CENTRAL_CHESOEN", "BOMET_BOMET_CENTRAL_MUTARAKWA"),
                second,
                "a later successful lookup should not be shadowed by the earlier failure");
    }

    @Test
    void aMismatchedResponseCannotWidenScopeAndIsNotCached() {
        Map<String, Object> wrongRoot = Map.of("code", "OTHER_COUNTY", "children", List.of());
        Map<String, Object> mismatched = Map.of(
                "TenantBoundary", List.of(Map.of("boundary", List.of(wrongRoot))));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(mismatched)
                .thenReturn(nestedBoundaryResponse());

        Set<String> first = expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", "BOMET");
        Set<String> second = expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", "BOMET");

        assertEquals(Set.of("BOMET"), first, "a mismatched tree must never widen the ABAC allow-list");
        assertEquals(
                Set.of("BOMET", "BOMET_BOMET_CENTRAL", "BOMET_BOMET_CENTRAL_CHESOEN", "BOMET_BOMET_CENTRAL_MUTARAKWA"),
                second,
                "a later valid response should not be shadowed by the rejected response");
        verify(restTemplate, times(2)).postForObject(anyString(), any(), eq(Map.class));
    }

    // ── v3 ────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void v3IsAGetWithNoBodyAndTheTenantInAHeader() {
        onV3(v3NestedBoundaryResponse());

        expander.descendantsOf(employeeIn("ke.bomet"), "ke.bomet", "ADMIN", "BOMET");

        ArgumentCaptor<HttpMethod> method = ArgumentCaptor.forClass(HttpMethod.class);
        ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Map> headers = ArgumentCaptor.forClass(Map.class);
        verify(serviceRequestRepository).exchange(method.capture(), uri.capture(), body.capture(),
                headers.capture(), eq(Map.class));

        assertEquals(HttpMethod.GET, method.getValue());
        assertTrue(uri.getValue().startsWith("http://localhost:8081/boundary/v3/relationship?"), uri.getValue());
        assertTrue(uri.getValue().contains("hierarchyType=ADMIN"), uri.getValue());
        assertTrue(uri.getValue().contains("codes=BOMET"), uri.getValue());
        assertTrue(uri.getValue().contains("includeChildren=true"), uri.getValue());
        assertFalse(uri.getValue().contains("tenantId="), uri.getValue());
        assertEquals("ke.bomet", headers.getValue().get(V3Headers.TENANT_ID));
        // 2.x wrapped the RequestInfo here (while the sibling existence check posted a bare one);
        // v3 reads no body on a read at all.
        assertNull(body.getValue());
        verify(restTemplate, never()).postForObject(anyString(), any(), eq(Map.class));
    }

    @Test
    void v3FlattensTheLowercaseTenantBoundaryEnvelopeAndItsChildrenlessLeaves() {
        onV3(v3NestedBoundaryResponse());

        Set<String> codes = expander.descendantsOf(employeeIn("ke.bomet"), "ke.bomet", "ADMIN", "BOMET");

        // The whole migration in one assertion. 2.x spells the envelope "TenantBoundary" and v3
        // spells it "tenantBoundary", and this is a literal case-sensitive JsonNode lookup that
        // Jackson's case-insensitive binding does not rescue. Getting it wrong yields this same
        // call returning Set.of("BOMET") - an employee seeing zero complaints, HTTP 200, no error
        // anywhere, for the next thirty minutes.
        assertEquals(Set.of("BOMET", "BOMET_BOMET_CENTRAL", "BOMET_BOMET_CENTRAL_CHESOEN",
                "BOMET_BOMET_CENTRAL_MUTARAKWA"), codes);
    }

    @Test
    void v3AMismatchedRootStillCannotWidenScope() {
        Map<String, Object> wrongRoot = Map.of("id", "x", "code", "OTHER_COUNTY");
        Map<String, Object> mismatched = Map.of("tenantBoundary",
                List.of(Map.of("boundary", List.of(wrongRoot))));
        onV3(mismatched, v3NestedBoundaryResponse());

        Set<String> first = expander.descendantsOf(employeeIn("ke.bomet"), "ke.bomet", "ADMIN", "BOMET");
        Set<String> second = expander.descendantsOf(employeeIn("ke.bomet"), "ke.bomet", "ADMIN", "BOMET");

        assertEquals(Set.of("BOMET"), first, "a mismatched tree must never widen the ABAC allow-list");
        assertEquals(Set.of("BOMET", "BOMET_BOMET_CENTRAL", "BOMET_BOMET_CENTRAL_CHESOEN",
                "BOMET_BOMET_CENTRAL_MUTARAKWA"), second);
    }

    @Test
    void aWrongEnvelopeKeyDegradesToTheUnexpandedCodeAndIsNotCached() {
        // THE test whose absence cost two runs. The capital-T 2.x envelope arriving on the v3
        // path is exactly the mistake that was made: the response is well-formed, the status is
        // 200, Jackson binds it without complaint, and the employee's jurisdiction collapses to
        // the single unexpanded code.
        //
        // Degrading is correct - Set.of(code) is strictly MORE restrictive than a real expansion,
        // so it can never widen access. Degrading SILENTLY and then caching the degraded answer
        // for thirty minutes is not: the outage outlives its own fix, and `docker compose up -d`
        // does not even restart the container. So the second call must go back to the service.
        onV3(nestedBoundaryResponse(), v3NestedBoundaryResponse());

        Set<String> first = expander.descendantsOf(employeeIn("ke.bomet"), "ke.bomet", "ADMIN", "BOMET");
        Set<String> second = expander.descendantsOf(employeeIn("ke.bomet"), "ke.bomet", "ADMIN", "BOMET");

        assertEquals(Set.of("BOMET"), first, "an unreadable envelope must not be accepted as an expansion");
        assertEquals(Set.of("BOMET", "BOMET_BOMET_CENTRAL", "BOMET_BOMET_CENTRAL_CHESOEN",
                "BOMET_BOMET_CENTRAL_MUTARAKWA"), second, "the failure must not have been cached");
        verify(serviceRequestRepository, times(2)).exchange(any(HttpMethod.class), anyString(), any(),
                anyMap(), eq(Map.class));
    }

    @Test
    void theSameGuardAppliesToTheTwoXEnvelope() {
        // Symmetrically: the lowercase v3 envelope arriving on the 2.x path. This branch was
        // previously an unlogged, cached empty result on either stack.
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenReturn(v3NestedBoundaryResponse())
                .thenReturn(nestedBoundaryResponse());

        assertEquals(Set.of("BOMET"), expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", "BOMET"));
        assertEquals(Set.of("BOMET", "BOMET_BOMET_CENTRAL", "BOMET_BOMET_CENTRAL_CHESOEN",
                        "BOMET_BOMET_CENTRAL_MUTARAKWA"),
                expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", "BOMET"));
    }

    @Test
    void anEmptyResponseBodyDegradesRatherThanBeingCachedAsAnEmptyHierarchy() {
        onV3((Map<String, Object>) null, v3NestedBoundaryResponse());

        assertEquals(Set.of("BOMET"), expander.descendantsOf(employeeIn("ke.bomet"), "ke.bomet", "ADMIN", "BOMET"));
        assertEquals(Set.of("BOMET", "BOMET_BOMET_CENTRAL", "BOMET_BOMET_CENTRAL_CHESOEN",
                        "BOMET_BOMET_CENTRAL_MUTARAKWA"),
                expander.descendantsOf(employeeIn("ke.bomet"), "ke.bomet", "ADMIN", "BOMET"));
    }

    @Test
    void v3AnEmptyButPresentEnvelopeKeepsTheOldBehaviourOfDegradingToTheCode() {
        // "tenantBoundary":[] is a READABLE answer meaning "no such hierarchy", not a failure, so
        // it keeps degrading to the unexpanded code exactly as it did on 2.x.
        Map<String, Object> presentButEmpty = new LinkedHashMap<>();
        presentButEmpty.put("tenantBoundary", List.of());
        onV3(presentButEmpty);

        assertEquals(Set.of("BOMET"), expander.descendantsOf(employeeIn("ke.bomet"), "ke.bomet", "ADMIN", "BOMET"));
    }

    @Test
    void emptyOrNullInputsResolveToAnEmptySetWithoutCallingTheService() {
        assertEquals(Set.of(), expander.descendantsOf(new RequestInfo(), null, "ADMIN", "BOMET"));
        assertEquals(Set.of(), expander.descendantsOf(new RequestInfo(), "ke", null, "BOMET"));
        assertEquals(Set.of(), expander.descendantsOf(new RequestInfo(), "ke", "ADMIN", ""));

        verify(restTemplate, times(0)).postForObject(anyString(), any(), eq(Map.class));
    }
}
