package io.casehub.claudony.server.api;

import io.casehub.claudony.server.CaseBrowserResource;
import io.casehub.claudony.server.CasehubResource;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@McpDomain(value = "claudony/cases", basePath = "/api/claudony/cases")
@ApplicationScoped
public class ClaudonyCaseApi {

    @Inject CaseBrowserResource caseBrowser;
    @Inject CasehubResource casehubResource;

    @PlatformQuery("List CaseHub cases")
    @RestPath("/")
    public Object listCases() {
        return caseBrowser.listCases();
    }

    @PlatformQuery("Get case details")
    @RestPath("/{id}")
    public Object getCaseDetail(@PathParam UUID id) {
        return caseBrowser.getCaseDetail(id).getEntity();
    }

    @PlatformMutation("Start a CaseHub agent")
    @RestPath("/agent")
    public Object startAgent() {
        return casehubResource.startAgent().getEntity();
    }
}
