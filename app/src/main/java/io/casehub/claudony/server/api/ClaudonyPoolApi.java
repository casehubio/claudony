package io.casehub.claudony.server.api;

import io.casehub.claudony.server.fleet.ManagedSessionInfo;
import io.casehub.claudony.server.fleet.PoolDetail;
import io.casehub.claudony.server.fleet.PoolService;
import io.casehub.claudony.server.fleet.PoolSummary;
import io.casehub.claudony.server.fleet.PoolUpdateRequest;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@McpDomain(value = "claudony/pools", app = "claudony",
           basePath = "/api/claudony/pools",
           summary = "Agent pool management — capacity, scaling, sessions")
@ApplicationScoped
public class ClaudonyPoolApi {

    @Inject PoolService poolService;

    @PlatformQuery("List all pools")
    @RestPath("/")
    public List<PoolSummary> listPools() {
        return poolService.listPools();
    }

    @PlatformQuery("Get pool details")
    @RestPath("/{name}")
    public PoolDetail getPool(@PathParam String name) {
        return poolService.getPool(name);
    }

    @PlatformQuery("List pool sessions")
    @RestPath("/{name}/sessions")
    public List<ManagedSessionInfo> listSessions(@PathParam String name) {
        return poolService.listSessions(name);
    }

    @PlatformMutation("Update pool configuration")
    @RestPath("/{name}/update")
    public PoolDetail updatePool(@PathParam String name, PoolUpdateRequest request) {
        return poolService.updatePool(name, request);
    }

    @PlatformMutation("Suspend a pool session")
    @RestPath("/{name}/sessions/{id}/suspend")
    public void suspendSession(@PathParam String name, @PathParam String id) {
        poolService.suspendSession(name, id);
    }

    @PlatformMutation("Resume a pool session")
    @RestPath("/{name}/sessions/{id}/resume")
    public void resumeSession(@PathParam String name, @PathParam String id) {
        poolService.resumeSession(name, id);
    }

    @PlatformMutation("Destroy a pool session")
    @RestPath("/{name}/sessions/{id}/destroy")
    public void destroySession(@PathParam String name, @PathParam String id) {
        poolService.destroySession(name, id);
    }
}
