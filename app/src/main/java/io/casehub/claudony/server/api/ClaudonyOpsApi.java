package io.casehub.claudony.server.api;

import io.casehub.claudony.server.ops.FleetHealth;
import io.casehub.claudony.server.ops.OpsService;
import io.casehub.claudony.server.ops.OpsSnapshot;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@McpDomain(value = "claudony/ops", app = "claudony",
           basePath = "/api/claudony/ops",
           summary = "Fleet-wide operational visibility — topology, health, utilisation")
@ApplicationScoped
public class ClaudonyOpsApi {

    @Inject OpsService opsService;

    @PlatformQuery("Full operational snapshot")
    @RestPath("/snapshot")
    public OpsSnapshot snapshot() {
        return opsService.snapshot();
    }

    @PlatformQuery("Fleet health summary")
    @RestPath("/health")
    public FleetHealth health() {
        return opsService.health();
    }
}
