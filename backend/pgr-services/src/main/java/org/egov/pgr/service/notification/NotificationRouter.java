package org.egov.pgr.service.notification;

import lombok.extern.slf4j.Slf4j;
import org.egov.pgr.util.MDMSUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.egov.pgr.util.PGRConstants.SUBSCRIBER_ASSIGNEE;
import static org.egov.pgr.util.PGRConstants.SUBSCRIBER_CITIZEN;
import static org.egov.pgr.util.PGRConstants.SUBSCRIBER_CREATOR;
import static org.egov.pgr.util.PGRConstants.SUBSCRIBER_PREVIOUS_ASSIGNEE;

/**
 * The "who": resolves the RAINMAKER-PGR.NotificationRouting rows for a workflow transition
 * into the subscriber relationships + channels that should be notified. Replaces the hardcoded
 * NOTIFICATION_ENABLE_FOR_STATUS gate + the per-transition if-chains.
 *
 * Matching is on (businessService, action, toState). fromState is optional: a row with a null
 * fromState matches any source state (the Kafka consumer path doesn't carry fromState — risk R1).
 */
@Slf4j
@Component
public class NotificationRouter {

    private static final Set<String> VALID_SUBSCRIBERS = new HashSet<>(Arrays.asList(
            SUBSCRIBER_CITIZEN, SUBSCRIBER_ASSIGNEE, SUBSCRIBER_CREATOR, SUBSCRIBER_PREVIOUS_ASSIGNEE));

    private final MDMSUtils mdmsUtils;

    @Autowired
    public NotificationRouter(MDMSUtils mdmsUtils) {
        this.mdmsUtils = mdmsUtils;
    }

    @SuppressWarnings("unchecked")
    public List<RoutingMatch> route(String tenantId, String businessService, String fromState,
                                    String action, String toState) {
        List<RoutingMatch> matches = new ArrayList<>();
        if (!StringUtils.hasText(action) || !StringUtils.hasText(toState)) {
            return matches;
        }
        for (Object rowObj : mdmsUtils.getNotificationRouting(tenantId)) {
            if (!(rowObj instanceof Map)) continue;
            Map<String, Object> row = (Map<String, Object>) rowObj;

            if (Boolean.FALSE.equals(row.get("active"))) continue;
            if (!equalsIgnore(businessService, row.get("businessService"))) continue;
            if (!equalsIgnore(action, row.get("action"))) continue;
            if (!equalsIgnore(toState, row.get("toState"))) continue;

            Object rowFrom = row.get("fromState");
            // fromState optional: match when the row leaves it blank OR it equals the request's.
            if (rowFrom != null && StringUtils.hasText(fromState)
                    && !fromState.equalsIgnoreCase(rowFrom.toString())) continue;

            List<String> subscribers = validateSubscribers((List<String>) row.get("subscribers"), action, toState);
            List<String> channels = (List<String>) row.get("channels");
            if (subscribers.isEmpty() || channels == null || channels.isEmpty()) continue;

            matches.add(new RoutingMatch(subscribers, channels));
        }
        return matches;
    }

    private List<String> validateSubscribers(List<String> subscribers, String action, String toState) {
        List<String> valid = new ArrayList<>();
        if (subscribers == null) return valid;
        for (String s : subscribers) {
            if (s == null) continue;
            String code = s.trim().toUpperCase();
            if (VALID_SUBSCRIBERS.contains(code)) {
                valid.add(code);
            } else {
                log.warn("Ignoring unknown subscriber '{}' in NotificationRouting for action={} toState={} "
                        + "(must be one of {})", s, action, toState, VALID_SUBSCRIBERS);
            }
        }
        return valid;
    }

    private boolean equalsIgnore(String expected, Object actual) {
        return actual != null && expected != null && expected.equalsIgnoreCase(actual.toString());
    }
}
