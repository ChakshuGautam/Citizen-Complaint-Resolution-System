package org.egov.pgr.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.egov.common.contract.request.RequestInfo;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.identity.IndividualV3Client;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.web.models.User;
import org.egov.pgr.web.models.user.CreateUserRequest;
import org.egov.pgr.web.models.user.UserDetailResponse;
import org.egov.pgr.web.models.user.UserSearchRequest;
import org.egov.tracer.model.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which search {@code UserUtils.userCall} sends where, on each stack.
 *
 * <p>Both branches are exercised deliberately: a flag has to be able to go back, and a deployment
 * that flips and finds the new stack wrong must get yesterday's call byte for byte.
 */
class UserUtilsV3DispatchTest {

    private ServiceRequestRepository serviceRequestRepository;
    private IndividualV3Client individualV3Client;
    private PGRConfiguration config;
    private UserUtils userUtils;

    @BeforeEach
    void setUp() {
        serviceRequestRepository = mock(ServiceRequestRepository.class);
        individualV3Client = mock(IndividualV3Client.class);
        config = new PGRConfiguration();
        config.setUserHost("http://user:8081");
        config.setUserSearchEndpoint("/user/_search");
        config.setUserContextPath("/user/users");
        config.setUserCreateEndpoint("/_createnovalidate");
        config.setUserUpdateEndpoint("/_updatenovalidate");
        userUtils = new UserUtils(new ObjectMapper(), serviceRequestRepository, config, individualV3Client);
    }

    private UserSearchRequest search() {
        UserSearchRequest request = new UserSearchRequest();
        request.setTenantId("ke");
        request.setRequestInfo(new RequestInfo());
        request.setActive(true);
        return request;
    }

    @Test
    @DisplayName("2.x: a uuid search is still the POST it has always been, body and all")
    void twoXSearchIsUnchanged() {
        config.setUserApiVersion(PGRConfiguration.API_VERSION_2X);
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("user", Collections.emptyList());
        when(serviceRequestRepository.fetchResult(any(), any())).thenReturn(response);

        UserSearchRequest request = search();
        request.setUuid(Collections.singletonList("legacy-uuid"));
        userUtils.userCall(request, new StringBuilder("http://user:8081/user/_search"));

        verify(serviceRequestRepository).fetchResult(any(), eq(request));
        verify(individualV3Client, never()).searchByLegacyUuids(anyCollection(), anyString(), any());
    }

    @Test
    @DisplayName("v3: a uuid search becomes an individual search and never touches the 2.x transport")
    void v3UuidSearch() {
        config.setUserApiVersion(PGRConfiguration.API_VERSION_V3);
        when(individualV3Client.searchByLegacyUuids(anyCollection(), eq("ke"), any()))
                .thenReturn(Collections.singletonList(User.builder().uuid("legacy-uuid").build()));

        UserSearchRequest request = search();
        request.setUuid(Arrays.asList("legacy-uuid"));
        UserDetailResponse response = userUtils.userCall(request, new StringBuilder("http://user:8081/user/_search"));

        assertEquals("legacy-uuid", response.getUser().get(0).getUuid());
        verify(serviceRequestRepository, never()).fetchResult(any(), any());
    }

    @Test
    @DisplayName("v3: a CITIZEN userName search becomes a mobileNumber search — the one translation 2.x's own invariant licenses")
    void v3UserNameSearchBecomesMobile() {
        config.setUserApiVersion(PGRConfiguration.API_VERSION_V3);
        when(individualV3Client.searchByMobileNumber(eq("9000000000"), eq("ke"), any()))
                .thenReturn(Collections.emptyList());

        UserSearchRequest request = search();
        request.setUserType("CITIZEN");
        request.setUserName("9000000000");
        userUtils.userCall(request, new StringBuilder("http://user:8081/user/_search"));

        verify(individualV3Client).searchByMobileNumber(eq("9000000000"), eq("ke"), any());
    }

    @Test
    @DisplayName("v3: an EMPLOYEE userName search is refused — a staff code is not a mobile number")
    void v3EmployeeUserNameSearchIsRefused() {
        config.setUserApiVersion(PGRConfiguration.API_VERSION_V3);
        UserSearchRequest request = search();
        request.setUserType("EMPLOYEE");
        request.setUserName("PGGRO1");

        CustomException e = assertThrows(CustomException.class,
                () -> userUtils.userCall(request, new StringBuilder("http://user:8081/user/_search")));
        assertEquals("USER_FILTER_UNSUPPORTED_ON_V3", e.getCode());
    }

    @Test
    @DisplayName("v3: a role-filtered search is refused — there is no role filter and no roles field")
    void v3RoleFilteredSearchIsRefused() {
        config.setUserApiVersion(PGRConfiguration.API_VERSION_V3);
        UserSearchRequest request = search();
        request.setRoleCodes(Collections.singletonList("PGR_LME"));

        CustomException e = assertThrows(CustomException.class,
                () -> userUtils.userCall(request, new StringBuilder("http://user:8081/user/_search")));
        assertEquals("USER_FILTER_UNSUPPORTED_ON_V3", e.getCode());
    }

    @Test
    @DisplayName("v3: a search with nothing that filters is refused rather than answered with the whole tenant")
    void v3UnfilteredSearchIsRefused() {
        config.setUserApiVersion(PGRConfiguration.API_VERSION_V3);
        CustomException e = assertThrows(CustomException.class,
                () -> userUtils.userCall(search(), new StringBuilder("http://user:8081/user/_search")));
        assertEquals("USER_FILTER_UNSUPPORTED_ON_V3", e.getCode());
    }

    @Test
    @DisplayName("v3: a create/update is refused until it is wired, rather than writing half a person")
    void v3WriteIsRefusedForNow() {
        config.setUserApiVersion(PGRConfiguration.API_VERSION_V3);
        CreateUserRequest request = new CreateUserRequest(new RequestInfo(), User.builder().name("Asha").build());
        CustomException e = assertThrows(CustomException.class,
                () -> userUtils.userCall(request, new StringBuilder("http://user:8081/user/users/_createnovalidate")));
        assertEquals("USER_WRITE_UNSUPPORTED_ON_V3", e.getCode());
    }
}
