package org.egov.pgr.identity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The individual registry's wire contract, driven through a real RestTemplate.
 *
 * <p>Every canned reply here is a shape that was read off the running v3 build on 2026-09-21, not
 * one derived from the spec: the two disagree, and on this endpoint they disagree about the
 * envelope key, which is exactly the kind of difference CCRS's mapper swallows without a word.
 */
class IndividualV3ClientTest {

    private static final String LEGACY = "629b9122-1111-2222-3333-444444444444";
    private static final String V3 = "bf2557f5-7b46-4be7-99a9-848228d41f6d";
    private static final String TENANT = "ke";
    private static final String ACTOR = "actor-uuid";
    private static final String BASE = "http://individual:8080/individual/v3/individuals";

    private MockRestServiceServer server;
    private IdentityMapService identityMap;
    private IndividualV3Client client;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().removeIf(c -> c instanceof MappingJackson2HttpMessageConverter);
        restTemplate.getMessageConverters().add(new MappingJackson2HttpMessageConverter(objectMapper));
        server = MockRestServiceServer.createServer(restTemplate);

        PGRConfiguration config = new PGRConfiguration();
        config.setUserApiVersion(PGRConfiguration.API_VERSION_V3);
        config.setUserV3Host("http://individual:8080");
        config.setUserV3IndividualPath("/individual/v3/individuals");
        config.setV3PageMaxSize(100);
        config.setWorkflowV3MaxPages(50);

        identityMap = mock(IdentityMapService.class);
        client = new IndividualV3Client(new ServiceRequestRepository(objectMapper, restTemplate), config, identityMap);
    }

    private RequestInfo requestInfo(String actorUuid) {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(User.builder().uuid(actorUuid).tenantId(TENANT).build());
        return requestInfo;
    }

    private String oneIndividual(String id) {
        return "{\"totalCount\":1,\"page\":1,\"size\":100,\"hasMore\":false,\"individuals\":[{"
                + "\"id\":\"" + id + "\",\"individualId\":\"IND-40d04697\",\"givenName\":\"Asha\","
                + "\"familyName\":\"Kiptoo\",\"gender\":\"FEMALE\",\"mobileNumber\":\"9000000000\","
                + "\"email\":\"asha@example.org\",\"isActive\":true,\"version\":3}]}";
    }

    @Test
    @DisplayName("the search is a GET with the REPEATED id form, size and a 1-based page, and no body")
    void searchShape() {
        server.expect(requestTo(allOf(
                        containsString("id=" + V3),
                        containsString("size=100"),
                        // v3's individual paging is 1-BASED and page=0 is a 400; the workflow
                        // service's is 0-based. They are different services and do not agree.
                        containsString("page=1"),
                        // the comma form is refused outright by the registry ("id[0] failed uuid
                        // validation"), so CCRS never builds it, on either stack's behalf
                        not(containsString(",")),
                        not(containsString("userType=")),
                        not(containsString("active=")),
                        not(containsString("tenantId=")))))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Tenant-ID", TENANT))
                .andExpect(header("X-User-ID", ACTOR))
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess(oneIndividual(V3), MediaType.APPLICATION_JSON));

        when(identityMap.toV3All(eq(SubjectKind.INDIVIDUAL), anyCollection()))
                .thenReturn(Collections.singletonMap(LEGACY, V3));

        List<org.egov.pgr.web.models.User> users =
                client.searchByLegacyUuids(Collections.singletonList(LEGACY), TENANT, requestInfo(ACTOR));

        server.verify();
        assertEquals(1, users.size());
    }

    @Test
    @DisplayName("the answer comes back wearing the 2.x uuid, and the fields v3 has no column for are null")
    void reverseSubstitutionAndFieldMapping() {
        server.expect(requestTo(containsString("id=" + V3)))
                .andRespond(withSuccess(oneIndividual(V3), MediaType.APPLICATION_JSON));
        when(identityMap.toV3All(eq(SubjectKind.INDIVIDUAL), anyCollection()))
                .thenReturn(Collections.singletonMap(LEGACY, V3));

        org.egov.pgr.web.models.User user =
                client.searchByLegacyUuids(Collections.singletonList(LEGACY), TENANT, requestInfo(ACTOR)).get(0);

        assertEquals(LEGACY, user.getUuid());
        assertEquals("Asha Kiptoo", user.getName());
        // v3 spells it `email`; `emailId`, the 2.x spelling, is rejected as an unknown field on
        // create. A rename, not a case difference - CCRS's mapper would NOT have bridged it.
        assertEquals("asha@example.org", user.getEmailId());
        // 2.x set userName to the mobile number for every citizen it created; v3 has no userName.
        assertEquals("9000000000", user.getUserName());
        assertEquals(TENANT, user.getTenantId());
        assertEquals(Boolean.TRUE, user.getActive());
        assertNull(user.getId());
        assertNull(user.getType());
        assertNull(user.getCountryCode());
        assertEquals(Collections.emptyList(), user.getRoles());
    }

    @Test
    @DisplayName("an unmapped uuid is refused BY NAME and nothing is sent — never passed through to v3 raw")
    void unmappedUuidsAreNamedAndRefused() {
        when(identityMap.toV3All(eq(SubjectKind.INDIVIDUAL), anyCollection()))
                .thenReturn(Collections.singletonMap(LEGACY, V3));

        CustomException e = assertThrows(CustomException.class, () ->
                client.searchByLegacyUuids(Arrays.asList(LEGACY, "unmapped-a", "unmapped-b"), TENANT, requestInfo(ACTOR)));

        assertEquals("IDENTITY_MAP_MISS", e.getCode());
        assertTrue(e.getMessage().contains("unmapped-a"));
        assertTrue(e.getMessage().contains("unmapped-b"));
        server.verify();   // no request was made at all
    }

    @Test
    @DisplayName("THE WRONG-KEY TEST: a `individual` envelope binds to nothing, so it must fail rather than answer 'nobody'")
    void singularEnvelopeKeyIsAFailureNotAnEmptyResult() {
        // The spec and the C3 contract sketch both say `individual`. The running build says
        // `individuals`. Under ACCEPT_CASE_INSENSITIVE_PROPERTIES + FAIL_ON_UNKNOWN_PROPERTIES=false
        // the wrong spelling is not an error, it is an empty user list - which reads downstream as
        // "this person does not exist" and attaches a null citizen at HTTP 200.
        server.expect(requestTo(containsString("id=" + V3)))
                .andRespond(withSuccess("{\"totalCount\":1,\"individual\":[{\"id\":\"" + V3 + "\"}]}",
                        MediaType.APPLICATION_JSON));
        when(identityMap.toV3All(eq(SubjectKind.INDIVIDUAL), anyCollection()))
                .thenReturn(Collections.singletonMap(LEGACY, V3));

        CustomException e = assertThrows(CustomException.class, () ->
                client.searchByLegacyUuids(Collections.singletonList(LEGACY), TENANT, requestInfo(ACTOR)));
        assertEquals("INDIVIDUAL_RESPONSE_UNREADABLE", e.getCode());
        assertTrue(e.getMessage().contains("individual"));
    }

    @Test
    @DisplayName("a reply shorter than v3's own totalCount is a truncation, not an answer")
    void shortAnswerIsRefused() {
        server.expect(requestTo(containsString("id=" + V3)))
                .andRespond(withSuccess("{\"totalCount\":4,\"hasMore\":false,\"individuals\":[{\"id\":\"" + V3
                        + "\",\"givenName\":\"Asha\"}]}", MediaType.APPLICATION_JSON));
        when(identityMap.toV3All(eq(SubjectKind.INDIVIDUAL), anyCollection()))
                .thenReturn(Collections.singletonMap(LEGACY, V3));

        CustomException e = assertThrows(CustomException.class, () ->
                client.searchByLegacyUuids(Collections.singletonList(LEGACY), TENANT, requestInfo(ACTOR)));
        assertEquals("INDIVIDUAL_RESULT_TRUNCATED", e.getCode());
    }

    @Test
    @DisplayName("hasMore walks to the next page rather than reporting a partial list as complete")
    void pagingWalks() {
        server.expect(requestTo(containsString("page=1")))
                .andRespond(withSuccess("{\"totalCount\":2,\"hasMore\":true,\"individuals\":[{\"id\":\"" + V3
                        + "\",\"givenName\":\"Asha\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("page=2")))
                .andRespond(withSuccess("{\"totalCount\":2,\"hasMore\":false,\"individuals\":[{\"id\":\"second-v3\",\"givenName\":\"Bea\"}]}",
                        MediaType.APPLICATION_JSON));

        Map<String, String> map = new LinkedHashMap<>();
        map.put(LEGACY, V3);
        map.put("second-legacy", "second-v3");
        when(identityMap.toV3All(eq(SubjectKind.INDIVIDUAL), anyCollection())).thenReturn(map);

        List<org.egov.pgr.web.models.User> users =
                client.searchByLegacyUuids(Arrays.asList(LEGACY, "second-legacy"), TENANT, requestInfo(ACTOR));

        server.verify();
        assertEquals(2, users.size());
    }

    @Test
    @DisplayName("more than 100 ids is chunked, because v3 refuses size>100 with a 400")
    void moreThanAPageOfIdsIsChunked() {
        server.expect(times(2), requestTo(containsString("size=100")))
                .andRespond(withSuccess("{\"totalCount\":0,\"hasMore\":false,\"individuals\":[]}",
                        MediaType.APPLICATION_JSON));

        Map<String, String> map = new LinkedHashMap<>();
        List<String> legacyUuids = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            map.put("legacy-" + i, "v3-" + i);
            legacyUuids.add("legacy-" + i);
        }
        when(identityMap.toV3All(eq(SubjectKind.INDIVIDUAL), anyCollection())).thenReturn(map);

        client.searchByLegacyUuids(legacyUuids, TENANT, requestInfo(ACTOR));
        server.verify();
    }

    @Test
    @DisplayName("a mobile-number search relabels through the reverse map, and refuses an individual CCRS cannot name")
    void mobileSearchGoesThroughTheReverseMap() {
        server.expect(requestTo(allOf(containsString("mobileNumber=9000000000"), containsString("size=100"))))
                .andRespond(withSuccess(oneIndividual(V3), MediaType.APPLICATION_JSON));
        when(identityMap.requireLegacy(SubjectKind.INDIVIDUAL, V3)).thenReturn(LEGACY);

        List<org.egov.pgr.web.models.User> users =
                client.searchByMobileNumber("9000000000", TENANT, requestInfo(ACTOR));
        assertEquals(LEGACY, users.get(0).getUuid());
        server.verify();
    }

    @Test
    @DisplayName("the actor header is mandatory on a SEARCH — measured, and not in the inventory")
    void actorHeaderIsMandatory() {
        CustomException e = assertThrows(CustomException.class, () ->
                client.searchByMobileNumber("9000000000", TENANT, requestInfo(null)));
        assertEquals("INDIVIDUAL_ACTOR_MISSING", e.getCode());
        server.verify();
    }

    @Test
    @DisplayName("a blank tenant is refused rather than defaulted to somebody else's")
    void tenantIsMandatory() {
        CustomException e = assertThrows(CustomException.class, () ->
                client.searchByMobileNumber("9000000000", "  ", requestInfo(ACTOR)));
        assertEquals("INDIVIDUAL_TENANT_MISSING", e.getCode());
        server.verify();
    }
}
