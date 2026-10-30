package io.casehub.claudony.server.api;

import io.casehub.claudony.casehub.fleet.ApplicationInfo;
import io.casehub.claudony.casehub.fleet.ApplicationRegistrationService;
import io.casehub.claudony.casehub.fleet.ApplicationSpec;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.QueryParam;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.List;

@McpDomain(value = "claudony/apps", app = "claudony",
           basePath = "/api/claudony/apps",
           summary = "Application registration — namespace, pools, lifecycle")
@ApplicationScoped
public class ClaudonyAppApi {

    @Inject ApplicationRegistrationService appService;

    @PlatformQuery("List registered applications")
    @RestPath("/")
    public List<ApplicationInfo> listApps(@QueryParam("namespace") String namespace) {
        if (namespace != null && !namespace.isBlank()) {
            return appService.list(namespace);
        }
        return appService.list();
    }

    @PlatformQuery("Get application details")
    @RestPath("/{name}")
    public ApplicationInfo getApp(@PathParam String name) {
        return appService.get(name)
                .orElseThrow(() -> new WebApplicationException(Response.Status.NOT_FOUND));
    }

    @PlatformMutation("Register an application")
    @RestPath("/")
    public ApplicationInfo registerApp(ApplicationSpec spec) {
        return appService.register(spec);
    }

    @PlatformMutation("Update an application")
    @RestPath("/{name}")
    public ApplicationInfo updateApp(@PathParam String name, ApplicationSpec spec) {
        return appService.update(name, spec);
    }


    @PlatformMutation("Deregister an application")
    @RestPath("/{name}/deregister")
    public void deregisterApp(@PathParam String name) {
        appService.deregister(name);
    }

    @PlatformMutation("Heartbeat an application")
    @RestPath("/{name}/heartbeat")
    public void heartbeat(@PathParam String name) {
        appService.heartbeat(name);
    }

    @PlatformMutation("Suspend an application")
    @RestPath("/{name}/suspend")
    public void suspendApp(@PathParam String name) {
        appService.suspend(name);
    }

    @PlatformMutation("Resume an application")
    @RestPath("/{name}/resume")
    public void resumeApp(@PathParam String name) {
        appService.resume(name);
    }

    @PlatformMutation("Add a pool to an application")
    @RestPath("/{name}/pools")
    public ApplicationInfo.AppPoolInfo addPool(@PathParam String name, ApplicationSpec.PoolSpec pool) {
        return appService.addPool(name, pool);
    }

    @PlatformMutation("Update a pool's capacity")
    @RestPath("/{name}/pools/{agentId}")
    public ApplicationInfo.AppPoolInfo updatePool(@PathParam String name, @PathParam String agentId,
                                                   ApplicationSpec.PoolSpec pool) {
        return appService.updatePool(name, agentId, pool);
    }

    @PlatformMutation("Remove a pool from an application")
    @RestPath("/{name}/pools/{agentId}/remove")
    public void removePool(@PathParam String name, @PathParam String agentId) {
        appService.removePool(name, agentId);
    }

}
