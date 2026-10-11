package io.casehub.claudony.server.api;

import io.casehub.claudony.casehub.fleet.ApplicationInfo;
import io.casehub.claudony.casehub.fleet.ApplicationRegistrationService;
import io.casehub.claudony.casehub.fleet.ApplicationSpec;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
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
    public List<ApplicationInfo> listApps() {
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
}
