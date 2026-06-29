package org.egov.pgr.service.notification;

import org.egov.pgr.util.MDMSUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class NotificationRouterTest {

    private static final String TENANT = "ke.bomet";

    @Mock
    private MDMSUtils mdmsUtils;

    @InjectMocks
    private NotificationRouter router;

    private Map<String, Object> row(String fromState, String action, String toState,
                                    List<String> subscribers, List<String> channels) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessService", "PGR");
        m.put("fromState", fromState);
        m.put("action", action);
        m.put("toState", toState);
        m.put("subscribers", subscribers);
        m.put("channels", channels);
        m.put("active", true);
        return m;
    }

    private void seed(Object... rows) {
        when(mdmsUtils.getNotificationRouting(TENANT)).thenReturn(new ArrayList<>(Arrays.asList(rows)));
    }

    @Test
    void assign_routesToCitizenAndAssignee() {
        seed(row(null, "ASSIGN", "PENDINGATLME", Arrays.asList("CITIZEN", "ASSIGNEE"), Arrays.asList("SMS")));
        List<RoutingMatch> matches = router.route(TENANT, "PGR", null, "ASSIGN", "PENDINGATLME");
        assertEquals(1, matches.size());
        assertEquals(Arrays.asList("CITIZEN", "ASSIGNEE"), matches.get(0).getSubscribers());
        assertEquals(Arrays.asList("SMS"), matches.get(0).getChannels());
    }

    @Test
    void apply_routesToCitizenOnly() {
        seed(row(null, "APPLY", "PENDINGFORASSIGNMENT", Arrays.asList("CITIZEN"), Arrays.asList("SMS")));
        List<RoutingMatch> matches = router.route(TENANT, "PGR", null, "APPLY", "PENDINGFORASSIGNMENT");
        assertEquals(1, matches.size());
        assertEquals(Arrays.asList("CITIZEN"), matches.get(0).getSubscribers());
    }

    @Test
    void rate_disambiguatesByToState() {
        seed(
            row("RESOLVED", "RATE", "CLOSEDAFTERRESOLUTION", Arrays.asList("ASSIGNEE"), Arrays.asList("SMS")),
            row("REJECTED", "RATE", "CLOSEDAFTERREJECTION", Arrays.asList("CITIZEN"), Arrays.asList("SMS"))
        );
        List<RoutingMatch> res = router.route(TENANT, "PGR", null, "RATE", "CLOSEDAFTERRESOLUTION");
        assertEquals(1, res.size());
        assertEquals(Arrays.asList("ASSIGNEE"), res.get(0).getSubscribers());
    }

    @Test
    void noMatch_returnsEmpty() {
        seed(row(null, "ASSIGN", "PENDINGATLME", Arrays.asList("CITIZEN"), Arrays.asList("SMS")));
        assertTrue(router.route(TENANT, "PGR", null, "COMMENT", "PENDINGATLME").isEmpty());
    }

    @Test
    void unknownSubscriber_isSkipped_validKept() {
        seed(row(null, "ASSIGN", "PENDINGATLME", Arrays.asList("CITIZEN", "GRO"), Arrays.asList("SMS")));
        List<RoutingMatch> res = router.route(TENANT, "PGR", null, "ASSIGN", "PENDINGATLME");
        assertEquals(Arrays.asList("CITIZEN"), res.get(0).getSubscribers());
    }

    @Test
    void allUnknownSubscribers_dropsRow() {
        seed(row(null, "ASSIGN", "PENDINGATLME", Arrays.asList("GRO", "PGR_VIEWER"), Arrays.asList("SMS")));
        assertTrue(router.route(TENANT, "PGR", null, "ASSIGN", "PENDINGATLME").isEmpty());
    }

    @Test
    void inactiveRow_isSkipped() {
        Map<String, Object> r = row(null, "ASSIGN", "PENDINGATLME", Arrays.asList("CITIZEN"), Arrays.asList("SMS"));
        r.put("active", false);
        seed(r);
        assertTrue(router.route(TENANT, "PGR", null, "ASSIGN", "PENDINGATLME").isEmpty());
    }

    @Test
    void fromState_optional_matchesWhenRowBlank() {
        seed(row(null, "ASSIGN", "PENDINGATLME", Arrays.asList("CITIZEN"), Arrays.asList("SMS")));
        assertEquals(1, router.route(TENANT, "PGR", "PENDINGFORREASSIGNMENT", "ASSIGN", "PENDINGATLME").size());
    }

    @Test
    void fromState_specific_filtersWhenSet() {
        seed(row("PENDINGATLME", "REASSIGN", "PENDINGFORREASSIGNMENT", Arrays.asList("CITIZEN"), Arrays.asList("SMS")));
        assertEquals(1, router.route(TENANT, "PGR", "PENDINGATLME", "REASSIGN", "PENDINGFORREASSIGNMENT").size());
        // different fromState supplied -> filtered out
        assertTrue(router.route(TENANT, "PGR", "SOMEWHERE", "REASSIGN", "PENDINGFORREASSIGNMENT").isEmpty());
    }

    @Test
    void blankActionOrToState_returnsEmpty() {
        seed(row(null, "ASSIGN", "PENDINGATLME", Arrays.asList("CITIZEN"), Arrays.asList("SMS")));
        assertTrue(router.route(TENANT, "PGR", null, null, "PENDINGATLME").isEmpty());
        assertTrue(router.route(TENANT, "PGR", null, "ASSIGN", null).isEmpty());
    }
}
