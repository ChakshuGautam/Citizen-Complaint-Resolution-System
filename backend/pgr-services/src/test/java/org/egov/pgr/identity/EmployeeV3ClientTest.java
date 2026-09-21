package org.egov.pgr.identity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * d3-employee, and the 2.x shape CCRS renders it into.
 *
 * <p>The filters here are the whole story: {@code ?id=}, {@code ?code=} and {@code ?userId=} are
 * all accepted and IGNORED by the running build, each returning every employee in the tenant at
 * HTTP 200. That is the same pathology as the individual registry's {@code ?userId=}, and on this
 * endpoint it would resolve a department, a designation and a jurisdiction scope from the wrong
 * person - silently. CCRS applies those filters itself.
 */
class EmployeeV3ClientTest {

    private static final String TENANT = "pg";
    private static final String ACTOR = "actor-uuid";
    private static final String GRO_LEGACY = "21fc9b81-9e52-43a5-ac1a-00db84dc2a6a";
    private static final String GRO_V3 = "1c2936c6-e6c8-42cc-ad70-432acee63b5a";
    private static final String LME_LEGACY = "165804ce-d0bb-4f2a-9472-e011571ea872";
    private static final String LME_V3 = "49f51551-ffb8-4031-bbd2-927760a91023";
    private static final String EMPLOYEES = "http://d3-employee:8080/employee/v3/employees";
    private static final String JURISDICTIONS = "http://d3-employee:8080/employee/v3/jurisdictions";

    private MockRestServiceServer server;
    private PGRConfiguration config;
    private IdentityMapService identityMap;
    private IndividualV3Client individualV3Client;
    private EmployeeV3Client client;

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
        config.setHrmsApiVersion(PGRConfiguration.API_VERSION_V3);
        config.setHrmsV3Host("http://d3-employee:8080");
        config.setHrmsV3EmployeeSearchEndpoint("/employee/v3/employees");
        config.setHrmsV3JurisdictionSearchEndpoint("/employee/v3/jurisdictions");
        config.setHrmsV3MaxEmployees(5000);

        identityMap = mock(IdentityMapService.class);
        individualV3Client = mock(IndividualV3Client.class);
        client = new EmployeeV3Client(new ServiceRequestRepository(objectMapper, restTemplate), config,
                identityMap, individualV3Client);
    }

    private RequestInfo requestInfo() {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(User.builder().uuid(ACTOR).tenantId(TENANT).build());
        return requestInfo;
    }

    private String employeeRow(String id, String code, String userId, String individualId) {
        return "{\"id\":\"" + id + "\",\"code\":\"" + code + "\",\"userId\":\"" + userId + "\","
                + "\"status\":\"EMPLOYED\",\"employeeType\":\"PERMANENT\","
                + "\"dateOfAppointment\":\"2026-09-21T02:53:36Z\",\"department\":\"DEPT_3\","
                + "\"designation\":\"COMM\",\"isActive\":true"
                + (individualId == null ? "" : ",\"individualId\":\"" + individualId + "\"") + "}";
    }

    private String tenantList() {
        return "[" + employeeRow(GRO_V3, "PGGRO1", GRO_LEGACY, null) + ","
                + employeeRow(LME_V3, "PGLME2", LME_LEGACY, null) + ","
                + employeeRow("other-v3", "PGWCSR4", "other-legacy", null) + "]";
    }

    private void expectNoJurisdictions() {
        server.expect(manyTimes(), requestTo(containsString("/employee/v3/jurisdictions")))
                .andRespond(withSuccess("{\"jurisdictions\":[]}", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("a mapped uuid is one by-id call on the PATH form — the only query that answers correctly")
    void mappedUuidUsesTheByIdPathForm() {
        when(identityMap.toV3(SubjectKind.EMPLOYEE, GRO_LEGACY)).thenReturn(GRO_V3);
        server.expect(requestTo(EMPLOYEES + "/" + GRO_V3)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(employeeRow(GRO_V3, "PGGRO1", GRO_LEGACY, null), MediaType.APPLICATION_JSON));
        expectNoJurisdictions();

        JsonNode employees = client.searchByLegacyUuids(Collections.singletonList(GRO_LEGACY), TENANT, requestInfo())
                .path("Employees");

        server.verify();
        assertEquals(1, employees.size());
        assertEquals(GRO_LEGACY, employees.get(0).path("uuid").asText());
    }

    @Test
    @DisplayName("an UNMAPPED uuid falls back to a tenant sweep filtered HERE — ?userId= returns the whole tenant")
    void unmappedUuidSweepsAndFiltersClientSide() {
        when(identityMap.toV3(eq(SubjectKind.EMPLOYEE), anyString())).thenReturn(null);
        server.expect(requestTo(EMPLOYEES)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(tenantList(), MediaType.APPLICATION_JSON));
        expectNoJurisdictions();

        JsonNode employees = client.searchByLegacyUuids(Collections.singletonList(GRO_LEGACY), TENANT, requestInfo())
                .path("Employees");

        server.verify();
        // Three came back from v3; ONE is the person asked about.
        assertEquals(1, employees.size());
        assertEquals("PGGRO1", employees.get(0).path("code").asText());
    }

    @Test
    @DisplayName("the correspondence v3 states in employee_v3.user_id is RECORDED, so the next lookup is one call")
    void derivedCorrespondenceIsRecorded() {
        when(identityMap.toV3(eq(SubjectKind.EMPLOYEE), anyString())).thenReturn(null);
        server.expect(requestTo(EMPLOYEES))
                .andRespond(withSuccess(tenantList(), MediaType.APPLICATION_JSON));
        expectNoJurisdictions();

        client.searchByLegacyUuids(Arrays.asList(GRO_LEGACY, LME_LEGACY), TENANT, requestInfo());

        verify(identityMap).record(SubjectKind.EMPLOYEE, GRO_LEGACY, TENANT, GRO_V3, "PGGRO1", null,
                IdentitySource.DERIVED_FROM_V3, "employee-v3-read");
        verify(identityMap).record(SubjectKind.EMPLOYEE, LME_LEGACY, TENANT, LME_V3, "PGLME2", null,
                IdentitySource.DERIVED_FROM_V3, "employee-v3-read");
    }

    @Test
    @DisplayName("a code lookup filters here too — ?code=PGGRO1 returns every employee in the tenant")
    void codeLookupFiltersClientSide() {
        server.expect(requestTo(EMPLOYEES))
                .andRespond(withSuccess(tenantList(), MediaType.APPLICATION_JSON));
        expectNoJurisdictions();

        JsonNode employees = client.searchByCodes(Collections.singletonList("PGLME2"), TENANT, requestInfo())
                .path("Employees");

        server.verify();
        assertEquals(1, employees.size());
        assertEquals("PGLME2", employees.get(0).path("code").asText());
    }

    @Test
    @DisplayName("the 2.x JsonPaths every caller uses still resolve against the synthesised shape")
    void twoXJsonPathsStillResolve() {
        when(identityMap.toV3(SubjectKind.EMPLOYEE, GRO_LEGACY)).thenReturn(GRO_V3);
        server.expect(requestTo(EMPLOYEES + "/" + GRO_V3))
                .andRespond(withSuccess(employeeRow(GRO_V3, "PGGRO1", GRO_LEGACY, null), MediaType.APPLICATION_JSON));
        expectNoJurisdictions();

        Object answer = new ObjectMapper().convertValue(
                client.searchByLegacyUuids(Collections.singletonList(GRO_LEGACY), TENANT, requestInfo()),
                java.util.Map.class);

        List<String> departments = JsonPath.read(answer, "$.Employees.*.assignments.*.department");
        assertEquals(Collections.singletonList("DEPT_3"), departments);
        List<String> designations = JsonPath.read(answer,
                "$.Employees.*.assignments[?(@.department=='DEPT_3')].designation");
        assertEquals(Collections.singletonList("COMM"), designations);
    }

    @Test
    @DisplayName("the assignments list is SYNTHESISED and says so — v3 has one current state and no history")
    void assignmentsAreSynthesisedAndLabelled() {
        when(identityMap.toV3(SubjectKind.EMPLOYEE, GRO_LEGACY)).thenReturn(GRO_V3);
        server.expect(requestTo(EMPLOYEES + "/" + GRO_V3))
                .andRespond(withSuccess(employeeRow(GRO_V3, "PGGRO1", GRO_LEGACY, null), MediaType.APPLICATION_JSON));
        expectNoJurisdictions();

        JsonNode assignment = client.searchByLegacyUuids(Collections.singletonList(GRO_LEGACY), TENANT, requestInfo())
                .path("Employees").get(0).path("assignments").get(0);

        assertEquals(1, assignment.path("syntheticFromV3ScalarDepartment").asBoolean() ? 1 : 0);
        assertTrue(assignment.path("isCurrentAssignment").asBoolean());
        // There is no supervisor column in employee_v3, no such field in any v3 employee request
        // or response, and zero hits for reportingTo across all sixteen v3 specs. It is NULL here
        // rather than invented, because an invented supervisor routes a real citizen's complaint
        // to the wrong person, silently.
        assertTrue(assignment.path("reportingTo").isNull());
    }

    @Test
    @DisplayName("a name is fetched from the OTHER registry when v3 says who the person is — the second call a shim cannot make")
    void nameFollowsIndividualId() {
        when(identityMap.toV3(SubjectKind.EMPLOYEE, GRO_LEGACY)).thenReturn(GRO_V3);
        server.expect(requestTo(EMPLOYEES + "/" + GRO_V3))
                .andRespond(withSuccess(employeeRow(GRO_V3, "PGGRO1", GRO_LEGACY, "ind-1"), MediaType.APPLICATION_JSON));
        expectNoJurisdictions();
        when(individualV3Client.readById(eq("ind-1"), eq(TENANT), any())).thenReturn(
                new ObjectMapper().createObjectNode().put("givenName", "Grace").put("familyName", "Otieno"));

        JsonNode employee = client.searchByLegacyUuids(Collections.singletonList(GRO_LEGACY), TENANT, requestInfo())
                .path("Employees").get(0);
        assertEquals("Grace Otieno", employee.path("user").path("name").asText());
    }

    @Test
    @DisplayName("no individualId means NO NAME — null, never the staff code standing in for a person")
    void missingIndividualIdLeavesTheNameNull() {
        when(identityMap.toV3(SubjectKind.EMPLOYEE, GRO_LEGACY)).thenReturn(GRO_V3);
        server.expect(requestTo(EMPLOYEES + "/" + GRO_V3))
                .andRespond(withSuccess(employeeRow(GRO_V3, "PGGRO1", GRO_LEGACY, null), MediaType.APPLICATION_JSON));
        expectNoJurisdictions();

        JsonNode employee = client.searchByLegacyUuids(Collections.singletonList(GRO_LEGACY), TENANT, requestInfo())
                .path("Employees").get(0);
        assertTrue(employee.path("user").path("name").isNull());
        verify(individualV3Client, never()).readById(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a stale map row that 404s falls back to the sweep rather than answering 'no such employee'")
    void staleMapRowFallsBackToTheSweep() {
        when(identityMap.toV3(SubjectKind.EMPLOYEE, GRO_LEGACY)).thenReturn("stale-v3-id");
        server.expect(requestTo(EMPLOYEES + "/stale-v3-id"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body("[{\"code\":\"NOT_FOUND\",\"message\":\"employee not found\"}]")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(EMPLOYEES))
                .andRespond(withSuccess(tenantList(), MediaType.APPLICATION_JSON));
        expectNoJurisdictions();

        JsonNode employees = client.searchByLegacyUuids(Collections.singletonList(GRO_LEGACY), TENANT, requestInfo())
                .path("Employees");
        server.verify();
        assertEquals("PGGRO1", employees.get(0).path("code").asText());
    }

    @Test
    @DisplayName("a tenant bigger than the cap is a refusal, because the endpoint is unpaged and a partial sweep lies")
    void oversizedTenantIsRefused() {
        config.setHrmsV3MaxEmployees(2);
        when(identityMap.toV3(eq(SubjectKind.EMPLOYEE), anyString())).thenReturn(null);
        server.expect(requestTo(EMPLOYEES))
                .andRespond(withSuccess(tenantList(), MediaType.APPLICATION_JSON));

        CustomException e = assertThrows(CustomException.class, () ->
                client.searchByLegacyUuids(Collections.singletonList(GRO_LEGACY), TENANT, requestInfo()));
        assertEquals("EMPLOYEE_SWEEP_TOO_LARGE", e.getCode());
    }

    @Test
    @DisplayName("a reply that is not the bare array v3 sends is a failure, not an empty employee list")
    void wrongShapeIsAFailure() {
        when(identityMap.toV3(eq(SubjectKind.EMPLOYEE), anyString())).thenReturn(null);
        server.expect(requestTo(EMPLOYEES))
                .andRespond(withSuccess("{\"Employees\":[]}", MediaType.APPLICATION_JSON));

        CustomException e = assertThrows(CustomException.class, () ->
                client.searchByLegacyUuids(Collections.singletonList(GRO_LEGACY), TENANT, requestInfo()));
        assertEquals("EMPLOYEE_RESPONSE_UNREADABLE", e.getCode());
    }

    @Test
    @DisplayName("both headers are mandatory and neither is defaulted")
    void headersAreMandatory() {
        assertEquals("EMPLOYEE_TENANT_MISSING", assertThrows(CustomException.class,
                () -> client.searchByCodes(Collections.singletonList("PGGRO1"), " ", requestInfo())).getCode());
        assertEquals("EMPLOYEE_ACTOR_MISSING", assertThrows(CustomException.class,
                () -> client.searchByCodes(Collections.singletonList("PGGRO1"), TENANT, new RequestInfo())).getCode());
        server.verify();
    }

    @Test
    @DisplayName("jurisdictions come from the separate flat collection, not from the employee row")
    void jurisdictionsComeFromTheirOwnEndpoint() {
        when(identityMap.toV3(SubjectKind.EMPLOYEE, GRO_LEGACY)).thenReturn(GRO_V3);
        server.expect(requestTo(EMPLOYEES + "/" + GRO_V3))
                .andRespond(withSuccess(employeeRow(GRO_V3, "PGGRO1", GRO_LEGACY, null), MediaType.APPLICATION_JSON));
        server.expect(requestTo(JURISDICTIONS + "?employeeId=" + GRO_V3)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"jurisdictions\":[{\"hierarchy\":\"ADMIN\",\"boundaryType\":\"City\","
                        + "\"boundary\":\"BOMET\",\"isActive\":true}]}", MediaType.APPLICATION_JSON));

        JsonNode jurisdictions = client.searchByLegacyUuids(Collections.singletonList(GRO_LEGACY), TENANT, requestInfo())
                .path("Employees").get(0).path("jurisdictions");
        server.verify();
        assertEquals("BOMET", jurisdictions.get(0).path("boundary").asText());
    }
}
