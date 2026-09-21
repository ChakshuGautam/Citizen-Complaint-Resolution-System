package org.egov.pgr.contract;

import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.pgr.identity.IdentityMapService;
import org.egov.pgr.identity.IndividualV3Client;
import org.egov.pgr.identity.SubjectKind;
import org.egov.pgr.service.UserService;
import org.egov.pgr.util.UserUtils;
import org.egov.pgr.web.models.Service;
import org.egov.pgr.web.models.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wire contract for the user/individual dependency.
 */
class V3UserContractTest extends V3ContractTestSupport {

    /** The 2.x uuid CCRS holds on the complaint. */
    private static final String CITIZEN_UUID = "629b9122-1111-2222-3333-444444444444";
    /** The id v3 minted for the same human, which CCRS must never let out of this layer. */
    private static final String V3_INDIVIDUAL_ID = "bf2557f5-7b46-4be7-99a9-848228d41f6d";

    private UserService userService;
    private ServiceRequest serviceRequest;

    @BeforeEach
    void wireCaller() {
        MultiStateInstanceUtil centralInstanceUtil = mock(MultiStateInstanceUtil.class);
        when(centralInstanceUtil.getStateLevelTenant(TENANT)).thenReturn(STATE_TENANT);

        IdentityMapService identityMap = mock(IdentityMapService.class);
        when(identityMap.toV3All(eq(SubjectKind.INDIVIDUAL), anyCollection()))
                .thenReturn(Collections.singletonMap(CITIZEN_UUID, V3_INDIVIDUAL_ID));

        IndividualV3Client individualV3Client = new IndividualV3Client(serviceRequestRepository, config, identityMap);
        UserUtils userUtils = new UserUtils(objectMapper, serviceRequestRepository, config, individualV3Client);
        ReflectionTestUtils.setField(userUtils, "centralInstanceUtil", centralInstanceUtil);
        userService = new UserService(userUtils, config);

        serviceRequest = ServiceRequest.builder()
                .requestInfo(requestInfo())
                .service(Service.builder().tenantId(TENANT).accountId(CITIZEN_UUID).build())
                .build();
    }

    @Test
    void individualSearchIsAGetOnRepeatedIdAndSendsNoFilterItCannotProve() {
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8081/individual/v3/individuals?"),
                        // C3 wrote this as id=<the 2.x uuid>, on the assumption that the uuid CCRS
                        // holds is the one v3 answers to. It is not, and that assumption is the
                        // whole reason the identity map exists: v3 mints its own id and ignores a
                        // supplied one, so the 2.x uuid must never appear on this wire at all.
                        containsString("id=" + V3_INDIVIDUAL_ID),
                        not(containsString(CITIZEN_UUID)),
                        // Only `id`, `individualId` and `mobileNumber` actually filter on the
                        // shipped build. ?userId=, ?givenName= and a nonsense parameter are all
                        // accepted and ignored, returning EVERY individual in the tenant with 200 -
                        // so an unproven filter is far worse than no filter. CCRS's 2.x searches
                        // carry userType and active as filters; neither survives.
                        not(containsString("userType=")),
                        not(containsString("active=")),
                        not(containsString("tenantId=")))))
                .andExpect(method(HttpMethod.GET))
                // CCRS searches at the state-level tenant while v3 resolves an individual at
                // whatever X-Tenant-ID says. The two tenancy models do not line up; pin whichever
                // one C16 settles on so the choice is visible rather than incidental.
                .andExpect(header("X-Tenant-ID", STATE_TENANT))
                // Measured on the live build and NOT in the inventory: the search is 400
                // "Missing required header: X-User-ID" without an actor, exactly like a write.
                .andExpect(header("X-User-ID", ACTOR_UUID))
                .andExpect(content().bytes(new byte[0]))
                // The envelope key is `individuals`, plural - the spec and the C3 sketch both say
                // `individual`. Under this mapper the singular spelling binds to nothing and
                // throws nothing, so the canned reply here is the shape the service really sends.
                .andRespond(withSuccess("{\"totalCount\":1,\"page\":1,\"size\":100,\"hasMore\":false,\"individuals\":[{\"id\":\"" + V3_INDIVIDUAL_ID + "\",\"individualId\":\"IND-40d04697\",\"givenName\":\"Asha\",\"familyName\":\"Kiptoo\",\"gender\":\"FEMALE\",\"mobileNumber\":\"9000000000\",\"isActive\":true,\"version\":1,\"auditDetail\":{\"createdBy\":\"sys\",\"createdTime\":1695000000000}}]}",
                        MediaType.APPLICATION_JSON));

        assertingOnlyTheRequest(() -> userService.callUserService(serviceRequest));

        // ...and the OUTCOME, because a request-only assertion cannot tell a working translation
        // from one that attaches nobody. This is the reverse substitution: the citizen on the
        // complaint wears the 2.x uuid CCRS already holds, not the id v3 answered with.
        org.egov.pgr.web.models.User citizen = serviceRequest.getService().getCitizen();
        assertEquals(CITIZEN_UUID, citizen.getUuid());
        assertEquals("Asha Kiptoo", citizen.getName());
        assertEquals("9000000000", citizen.getMobileNumber());
        // v3 has no numeric key, no user type and no country code on a phone number, and roles are
        // Keycloak realm roles rather than a property of an individual. Each of these is measured,
        // and each is permanently null on this path.
        assertNull(citizen.getId());
        assertNull(citizen.getType());
        assertNull(citizen.getCountryCode());
        assertEquals(Collections.emptyList(), citizen.getRoles());
    }
}
