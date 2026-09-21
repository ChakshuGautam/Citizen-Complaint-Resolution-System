package org.egov.pgr.service;

import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.pgr.config.PGRConfiguration;
import org.egov.pgr.repository.HrmsProjectionRepository;
import org.egov.pgr.repository.ServiceRequestRepository;
import org.egov.pgr.repository.SupervisorEdgeRepository;
import org.egov.pgr.util.MDMSUtils;
import org.egov.pgr.web.models.RequestSearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The silent half of the supervisor-edge problem.
 *
 * <p>Escalation fails LOUDLY without a supervisor edge - ESCALATION_TOP_OF_HIERARCHY, a 400 the
 * caller sees. The inbox does not. With no projected reportees, TEAM falls back to the tenant-wide
 * open view, and on DIGIT 3.0 - where v3 supplies no reportingTo on any employee, so every
 * projection row is null and every reportee walk comes back empty - that fallback turns EVERY
 * supervisor's "my team" inbox into "the whole tenant". Not an empty inbox, which somebody would
 * report; everyone else's citizens' complaints, which nobody would.
 *
 * <p>This is why CCRS owns the edge rather than just disabling escalation: a flag would have left
 * this half broken and quiet.
 */
class VisibilityTeamWideningTest {

    private static final String TENANT = "ke.bomet";
    private static final String ME = "supervisor-uuid";

    private PGRConfiguration config;
    private HrmsProjectionRepository projectionRepository;
    private SupervisorEdgeRepository supervisorEdgeRepository;
    private ServiceRequestRepository serviceRequestRepository;
    private VisibilityService visibilityService;

    @BeforeEach
    void setUp() {
        config = new PGRConfiguration();
        config.setVisibilityEnabled(true);
        projectionRepository = mock(HrmsProjectionRepository.class);
        supervisorEdgeRepository = mock(SupervisorEdgeRepository.class);
        serviceRequestRepository = mock(ServiceRequestRepository.class);
        MDMSUtils mdmsUtils = mock(MDMSUtils.class);
        when(mdmsUtils.getMdmsSearchUrl()).thenReturn(new StringBuilder("http://mdms/v1/_search"));
        when(serviceRequestRepository.fetchResult(any(), any())).thenReturn(visibilityConfigEnabled());

        visibilityService = new VisibilityService(config, projectionRepository, mock(WorkflowService.class),
                serviceRequestRepository, mdmsUtils, supervisorEdgeRepository);
    }

    /** The MDMS envelope the JsonPath in fetchVisibilityConfig reads. */
    private Map<String, Object> visibilityConfigEnabled() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("enabled", true);
        row.put("reporteeDepth", 3);
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("InboxVisibilityConfig", Collections.singletonList(row));
        Map<String, Object> mdmsRes = new LinkedHashMap<>();
        mdmsRes.put("RAINMAKER-PGR", module);
        Map<String, Object> root = new HashMap<>();
        root.put("MdmsRes", mdmsRes);
        return root;
    }

    private RequestInfo requestInfo() {
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setUserInfo(User.builder().uuid(ME).type("EMPLOYEE").tenantId(TENANT).build());
        return requestInfo;
    }

    private RequestSearchCriteria criteria() {
        RequestSearchCriteria criteria = new RequestSearchCriteria();
        criteria.setTenantId(TENANT);
        return criteria;
    }

    @Test
    @DisplayName("v3 with an EMPTY supervisor edge: TEAM is restricted to MINE, NOT widened to the tenant")
    void v3WithNoEdgeDoesNotWiden() {
        config.setHrmsApiVersion(PGRConfiguration.API_VERSION_V3);
        when(projectionRepository.getReporteeUuids(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptySet());
        when(supervisorEdgeRepository.countForTenant(TENANT)).thenReturn(0);

        RequestSearchCriteria criteria = criteria();
        visibilityService.resolve(requestInfo(), criteria, "TEAM");

        // Restricted, not widened. Showing a supervisor less than they are entitled to is
        // recoverable; showing them a whole tenant's citizen data is not.
        assertEquals(ME, criteria.getAssignee());
    }

    @Test
    @DisplayName("v3 with a POPULATED edge and a genuine leaf employee: the old tenant-wide fallback still applies")
    void v3WithAnEdgeKeepsTheLeafFallback() {
        config.setHrmsApiVersion(PGRConfiguration.API_VERSION_V3);
        when(projectionRepository.getReporteeUuids(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptySet());
        // The hierarchy IS known; this user is simply a leaf, which is what the fallback is for.
        when(supervisorEdgeRepository.countForTenant(TENANT)).thenReturn(14);

        RequestSearchCriteria criteria = criteria();
        visibilityService.resolve(requestInfo(), criteria, "TEAM");

        assertNull(criteria.getAssignee());
    }

    @Test
    @DisplayName("2.x is untouched: no reportees still means the tenant-wide open view")
    void twoXIsUnchanged() {
        config.setHrmsApiVersion(PGRConfiguration.API_VERSION_2X);
        when(projectionRepository.getReporteeUuids(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptySet());

        RequestSearchCriteria criteria = criteria();
        visibilityService.resolve(requestInfo(), criteria, "TEAM");

        assertNull(criteria.getAssignee());
    }
}
