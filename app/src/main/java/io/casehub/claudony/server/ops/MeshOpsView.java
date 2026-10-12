package io.casehub.claudony.server.ops;

import java.util.List;

public record MeshOpsView(int totalChannels, int totalInstances, List<ChannelOpsView> channels) {}
