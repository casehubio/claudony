package io.casehub.claudony.server.ops;

import java.time.Instant;
import java.util.List;

public record OpsSnapshot(
        FleetHealth health,
        List<PoolOpsView> pools,
        List<PeerOpsView> peers,
        List<AppOpsView> apps,
        MeshOpsView mesh,
        List<TopologyEdge> topology,
        Instant timestamp
) {}
