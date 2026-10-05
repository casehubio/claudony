package io.casehub.claudony.server.api;

import io.casehub.claudony.casehub.fleet.script.FleetScriptResult;
import io.casehub.claudony.server.fleet.FleetScriptService;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@McpDomain(value = "claudony/fleet", app = "claudony",
           basePath = "/api/claudony/fleet",
           summary = "Fleet deployment — execute declarative fleet scripts")
@ApplicationScoped
public class ClaudonyFleetApi {

    @Inject FleetScriptService fleetScriptService;

    @PlatformMutation("Execute a fleet deployment script. Parses YAML, topologically sorts nodes by dependencies, provisions pools and channels in order. Returns per-node success/failure results.")
    @RestPath("/execute")
    public FleetScriptResult executeFleetScript(String yaml) {
        return fleetScriptService.execute(yaml);
    }
}
