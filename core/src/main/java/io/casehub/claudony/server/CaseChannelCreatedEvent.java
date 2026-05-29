package io.casehub.claudony.server;

import java.util.UUID;

/**
 * Fired by ClaudonyReactiveCaseChannelProvider when a new Qhorus case channel is created.
 * Observed by ChannelFleetBroadcaster in claudony-app to propagate channel init to fleet peers.
 */
public record CaseChannelCreatedEvent(UUID channelId, String channelName) {}
