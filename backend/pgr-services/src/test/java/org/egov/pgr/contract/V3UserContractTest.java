package org.egov.pgr.contract;

import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.pgr.service.UserService;
import org.egov.pgr.util.UserUtils;
import org.egov.pgr.web.models.Service;
import org.egov.pgr.web.models.ServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
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

    private static final String CITIZEN_UUID = "629b9122-1111-2222-3333-444444444444";

    private UserService userService;

    @BeforeEach
    void wireCaller() {
        MultiStateInstanceUtil centralInstanceUtil = mock(MultiStateInstanceUtil.class);
        when(centralInstanceUtil.getStateLevelTenant(TENANT)).thenReturn(STATE_TENANT);

        UserUtils userUtils = new UserUtils(objectMapper, serviceRequestRepository, config);
        ReflectionTestUtils.setField(userUtils, "centralInstanceUtil", centralInstanceUtil);
        userService = new UserService(userUtils, config);
    }

    @Test
    @Disabled("Enabled by C16 — feat(user): move search to GET /individual/v3/individuals (needs the C15 identity map)")
    void individualSearchIsAGetOnRepeatedIdAndSendsNoFilterItCannotProve() {
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8081/individual/v3/individuals?"),
                        containsString("id=" + CITIZEN_UUID),
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
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess("{\"totalCount\":1,\"individual\":[{\"id\":\"" + CITIZEN_UUID + "\",\"tenantId\":\"ke\",\"givenName\":\"Asha\",\"familyName\":\"Kiptoo\",\"mobileNumber\":\"9000000000\",\"auditDetail\":{\"createdBy\":\"sys\",\"createdTime\":1695000000000}}]}",
                        MediaType.APPLICATION_JSON));

        ServiceRequest request = ServiceRequest.builder()
                .requestInfo(requestInfo())
                .service(Service.builder().tenantId(TENANT).accountId(CITIZEN_UUID).build())
                .build();

        assertingOnlyTheRequest(() -> userService.callUserService(request));
    }
}
