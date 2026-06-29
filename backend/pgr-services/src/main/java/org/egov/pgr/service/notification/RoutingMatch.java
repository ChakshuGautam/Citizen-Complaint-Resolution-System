package org.egov.pgr.service.notification;

import java.util.List;

/**
 * One matched NotificationRouting row: who to notify (subscriber relationship codes)
 * over which channels, for a (businessService, action, toState) transition.
 */
public class RoutingMatch {

    private final List<String> subscribers;
    private final List<String> channels;

    public RoutingMatch(List<String> subscribers, List<String> channels) {
        this.subscribers = subscribers;
        this.channels = channels;
    }

    public List<String> getSubscribers() {
        return subscribers;
    }

    public List<String> getChannels() {
        return channels;
    }
}
