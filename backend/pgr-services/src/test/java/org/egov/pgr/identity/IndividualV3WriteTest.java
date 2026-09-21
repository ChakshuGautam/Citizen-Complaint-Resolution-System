package org.egov.pgr.identity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.User;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Creating and updating a person in the DIGIT 3.0 individual registry.
 *
 * <p>The update half is the one place in this migration where being the BFF is strictly better
 * than being a shim, and the two measured reasons are asserted here: the record version, which
 * only a prior read supplies, and the fact that v3's PUT is a FULL REPLACE - a re-PUT without the
 * email field cleared the email at HTTP 200, where 2.x's _updatenovalidate merged.
 */
class IndividualV3WriteTest {

    private static final String LEGACY = "629b9122-1111-2222-3333-444444444444";
    private static final String V3 = "bf2557f5-7b46-4be7-99a9-848228d41f6d";
    private static final String TENANT = "ke";
    private static final String ACTOR = "actor-uuid";
    private static final String BASE = "http://individual:8080/individual/v3/individuals";

    private MockRestServiceServer server;
    private PGRConfiguration config;
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

        config = new PGRConfiguration();
        config.setUserApiVersion(PGRConfiguration.API_VERSION_V3);
        config.setUserV3Host("http://individual:8080");
        config.setUserV3IndividualPath("/individual/v3/individuals");
        config.setV3PageMaxSize(100);
        config.setWorkflowV3MaxPages(50);
        config.setUserV3IndividualUpdateRetries(1);

        identityMap = mock(IdentityMapService.class);
        client = new IndividualV3Client(new ServiceRequestRepository(objectMapper, restTemplate), config, identityMap);
    }

    private RequestInfo requestInfo() {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(org.egov.common.contract.request.User.builder().uuid(ACTOR).tenantId(TENANT).build());
        return requestInfo;
    }

    private String stored(String email, int version) {
        return "{\"id\":\"" + V3 + "\",\"individualId\":\"IND-1\",\"givenName\":\"Asha\",\"familyName\":\"Kiptoo\","
                + "\"gender\":\"FEMALE\",\"mobileNumber\":\"9000000000\""
                + (email == null ? "" : ",\"email\":\"" + email + "\"")
                + ",\"isActive\":true,\"version\":" + version + "}";
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("create refuses when no gender is configured — CCRS has no such field and will not invent one")
    void createWithoutGenderIsRefused() {
        CustomException e = assertThrows(CustomException.class, () ->
                client.create(User.builder().name("Asha Kiptoo").mobileNumber("9000000000").build(), TENANT, requestInfo()));
        assertEquals("INDIVIDUAL_GENDER_REQUIRED", e.getCode());
        assertTrue(e.getMessage().contains("MALE, FEMALE or OTHER"));
        server.verify();   // nothing was sent
    }

    @Test
    @DisplayName("create rejects a configured gender v3 does not accept, rather than being 400ed three frames away")
    void createWithInvalidConfiguredGender() {
        config.setUserV3IndividualDefaultGender("NOT_DISCLOSED");
        CustomException e = assertThrows(CustomException.class, () ->
                client.create(User.builder().name("Asha").build(), TENANT, requestInfo()));
        assertEquals("INDIVIDUAL_GENDER_INVALID", e.getCode());
    }

    @Test
    @DisplayName("create posts only fields v3 has a column for, carries the minted uuid as userId, and records the map")
    void createShapeAndMapRecording() {
        config.setUserV3IndividualDefaultGender("OTHER");
        server.expect(requestTo(BASE))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.givenName").value("Asha"))
                .andExpect(jsonPath("$.familyName").value("Kiptoo"))
                .andExpect(jsonPath("$.gender").value("OTHER"))
                .andExpect(jsonPath("$.mobileNumber").value("9000000000"))
                // v3 rejects these outright as unknown fields (400), so they must not be sent:
                .andExpect(content().string(allOf(
                        not(containsString("userName")),
                        not(containsString("\"type\"")),
                        not(containsString("roles")))))
                .andRespond(withSuccess(stored(null, 1), MediaType.APPLICATION_JSON));

        User created = client.create(User.builder().name("Asha Kiptoo").mobileNumber("9000000000")
                .userName("9000000000").type("CITIZEN").build(), TENANT, requestInfo());

        server.verify();
        // CCRS keeps minting 2.x uuids: the accountId column stays in one identifier space.
        ArgumentCaptor<String> legacyUuid = ArgumentCaptor.forClass(String.class);
        verify(identityMap).record(eq(SubjectKind.INDIVIDUAL), legacyUuid.capture(), eq(TENANT), eq(V3),
                eq("IND-1"), eq(null), eq(IdentitySource.PGR_CREATE), eq(ACTOR));
        assertEquals(legacyUuid.getValue(), created.getUuid());
        assertTrue(!V3.equals(created.getUuid()));
    }

    @Test
    @DisplayName("a single-word name becomes a givenName alone — familyName is optional, so nothing is duplicated")
    void singleWordName() {
        config.setUserV3IndividualDefaultGender("OTHER");
        server.expect(requestTo(BASE))
                .andExpect(jsonPath("$.givenName").value("Asha"))
                .andExpect(content().string(not(containsString("familyName"))))
                .andRespond(withSuccess(stored(null, 1), MediaType.APPLICATION_JSON));

        client.create(User.builder().name("Asha").build(), TENANT, requestInfo());
        server.verify();
    }

    @Test
    @DisplayName("a correspondence address is refused by name — the registry has nowhere to put it")
    void correspondenceAddressIsRefused() {
        config.setUserV3IndividualDefaultGender("OTHER");
        CustomException e = assertThrows(CustomException.class, () ->
                client.create(User.builder().name("Asha").correspondenceAddress("12 Main St").build(),
                        TENANT, requestInfo()));
        assertEquals("INDIVIDUAL_FIELD_UNSUPPORTED", e.getCode());
        server.verify();
    }

    // ------------------------------------------------------------------ update

    @Test
    @DisplayName("update READS FIRST for the version, then PUTs — a PUT with no version is a 409")
    void updateReadsThenWrites() {
        when(identityMap.requireV3(SubjectKind.INDIVIDUAL, LEGACY)).thenReturn(V3);
        server.expect(requestTo(BASE + "/" + V3)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(stored("asha@example.org", 7), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/" + V3)).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.givenName").value("Asha"))
                .andExpect(jsonPath("$.familyName").value("Kiptoo-Mwangi"))
                .andRespond(withSuccess(stored("asha@example.org", 8), MediaType.APPLICATION_JSON));

        User updated = client.update(User.builder().uuid(LEGACY).name("Asha Kiptoo-Mwangi").build(),
                TENANT, requestInfo());

        server.verify();
        assertEquals(LEGACY, updated.getUuid());
    }

    @Test
    @DisplayName("THE FULL-REPLACE TEST: fields CCRS does not model survive the PUT, because the read is OVERLAID not replaced")
    void unmodelledFieldsSurviveTheUpdate() {
        // Measured on the running build: a PUT without `email` CLEARED the email, at HTTP 200.
        // CCRS's User has no gender at all, and gender is mandatory - so a PUT built from what
        // CCRS holds would wipe the email and 400 on the missing gender. The read exists to stop
        // exactly that, and this test would fail if update ever went back to sending its own body.
        when(identityMap.requireV3(SubjectKind.INDIVIDUAL, LEGACY)).thenReturn(V3);
        server.expect(requestTo(BASE + "/" + V3)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(stored("asha@example.org", 3), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/" + V3)).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.email").value("asha@example.org"))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.mobileNumber").value("9000000000"))
                .andRespond(withSuccess(stored("asha@example.org", 4), MediaType.APPLICATION_JSON));

        client.update(User.builder().uuid(LEGACY).name("Asha Kiptoo").build(), TENANT, requestInfo());
        server.verify();
    }

    @Test
    @DisplayName("a 409 ROW_VERSION_MISMATCH re-reads the version and retries once")
    void versionConflictIsRetriedOnce() {
        when(identityMap.requireV3(SubjectKind.INDIVIDUAL, LEGACY)).thenReturn(V3);
        server.expect(requestTo(BASE + "/" + V3)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(stored(null, 1), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/" + V3)).andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .body("[{\"code\":\"ROW_VERSION_MISMATCH\",\"message\":\"Row version mismatch\",\"params\":{\"expected\":2,\"provided\":1}}]")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/" + V3)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(stored(null, 2), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/" + V3)).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.version").value(2))
                .andRespond(withSuccess(stored(null, 3), MediaType.APPLICATION_JSON));

        client.update(User.builder().uuid(LEGACY).name("Asha Kiptoo").build(), TENANT, requestInfo());
        server.verify();
    }

    @Test
    @DisplayName("a conflict on every attempt is a refusal, never a blind overwrite")
    void exhaustedRetriesRefuse() {
        config.setUserV3IndividualUpdateRetries(0);
        when(identityMap.requireV3(SubjectKind.INDIVIDUAL, LEGACY)).thenReturn(V3);
        server.expect(requestTo(BASE + "/" + V3)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(stored(null, 1), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/" + V3)).andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .body("[{\"code\":\"ROW_VERSION_MISMATCH\",\"message\":\"Row version mismatch\"}]")
                        .contentType(MediaType.APPLICATION_JSON));

        CustomException e = assertThrows(CustomException.class, () ->
                client.update(User.builder().uuid(LEGACY).name("Asha").build(), TENANT, requestInfo()));
        assertEquals("INDIVIDUAL_UPDATE_CONFLICT", e.getCode());
        // v3's own wording survives: on a concurrency failure the message IS the diagnosis.
        assertTrue(e.getMessage().contains("ROW_VERSION_MISMATCH"));
        server.verify();
    }

    @Test
    @DisplayName("a read that carries no version is a failure, not a guess about which write wins")
    void missingVersionIsRefused() {
        when(identityMap.requireV3(SubjectKind.INDIVIDUAL, LEGACY)).thenReturn(V3);
        server.expect(requestTo(BASE + "/" + V3))
                .andRespond(withSuccess("{\"id\":\"" + V3 + "\",\"givenName\":\"Asha\"}", MediaType.APPLICATION_JSON));

        CustomException e = assertThrows(CustomException.class, () ->
                client.update(User.builder().uuid(LEGACY).name("Asha").build(), TENANT, requestInfo()));
        assertEquals("INDIVIDUAL_VERSION_UNREADABLE", e.getCode());
        server.verify();
    }

    @Test
    @DisplayName("updating somebody the map does not know never reaches the wire")
    void unmappedUpdateNeverReachesTheWire() {
        when(identityMap.requireV3(eq(SubjectKind.INDIVIDUAL), anyString()))
                .thenThrow(new CustomException("IDENTITY_MAP_MISS", "no"));
        assertThrows(CustomException.class, () ->
                client.update(User.builder().uuid(LEGACY).name("Asha").build(), TENANT, requestInfo()));
        server.verify();
    }
}
