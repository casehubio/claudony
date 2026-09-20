package io.casehub.claudony.server.api;

import io.casehub.claudony.server.ActionInboxResource;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@McpDomain(value = "claudony/actions", basePath = "/api/claudony/actions")
@ApplicationScoped
public class ClaudonyActionApi {

    @Inject ActionInboxResource resource;

    @PlatformQuery("List pending actions in the inbox")
    @RestPath("/")
    public Object listActions() {
        return resource.listActions();
    }
}
