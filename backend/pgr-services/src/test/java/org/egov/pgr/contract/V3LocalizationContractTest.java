package org.egov.pgr.contract;

import org.egov.common.utils.MultiStateInstanceUtil;
import org.egov.pgr.producer.Producer;
import org.egov.pgr.util.NotificationUtil;
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
 * Wire contract for the localization dependency.
 */
class V3LocalizationContractTest extends V3ContractTestSupport {

    private NotificationUtil notificationUtil;

    @BeforeEach
    void wireCaller() {
        MultiStateInstanceUtil centralInstanceUtil = mock(MultiStateInstanceUtil.class);
        when(centralInstanceUtil.getStateLevelTenant(TENANT)).thenReturn(STATE_TENANT);

        notificationUtil = new NotificationUtil();
        ReflectionTestUtils.setField(notificationUtil, "serviceRequestRepository", serviceRequestRepository);
        ReflectionTestUtils.setField(notificationUtil, "config", config);
        ReflectionTestUtils.setField(notificationUtil, "producer", mock(Producer.class));
        ReflectionTestUtils.setField(notificationUtil, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(notificationUtil, "centralInstanceUtil", centralInstanceUtil);
    }

    @Test
    @Disabled("Enabled by C4 — feat(localization): move to GET /localization/v3/messages")
    void localizationSearchIsAGetWithTheTenantInAHeader() {
        server.expect(requestTo(allOf(
                        startsWith("http://localhost:8087/localization/v3/messages?"),
                        containsString("locale=en_IN"),
                        containsString("module=rainmaker-pgr"),
                        // v3 carries tenancy in X-Tenant-ID. Leaving ?tenantId= on the query would
                        // imply the two stacks agree on where tenancy lives; they do not.
                        not(containsString("tenantId=")))))
                .andExpect(method(HttpMethod.GET))
                // Localization is resolved at the state-level tenant, as it is on 2.x.
                .andExpect(header("X-Tenant-ID", STATE_TENANT))
                // A v3 read carries no RequestInfo; a body makes it malformed.
                .andExpect(content().bytes(new byte[0]))
                .andRespond(withSuccess("{\"messages\":[{\"code\":\"pgr.complaint.created\",\"message\":\"Your complaint is registered\",\"module\":\"rainmaker-pgr\",\"locale\":\"en_IN\",\"uuid\":\"7b1b\"}]}",
                        MediaType.APPLICATION_JSON));

        assertingOnlyTheRequest(() -> notificationUtil.getLocalizationMessages(TENANT, requestInfo(), "rainmaker-pgr"));
    }
}
