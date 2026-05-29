package io.casehub.claudony.server.fleet;

import java.util.UUID;

public record ChannelSyncRequest(UUID channelId, String channelName) {}
