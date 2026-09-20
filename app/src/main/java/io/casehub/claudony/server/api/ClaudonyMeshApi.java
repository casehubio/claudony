package io.casehub.claudony.server.api;

import io.casehub.claudony.server.MeshResource;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@McpDomain(value = "claudony/mesh", basePath = "/api/claudony/mesh")
@ApplicationScoped
public class ClaudonyMeshApi {

    @Inject MeshResource resource;

    @PlatformQuery("Get mesh configuration")
    @RestPath("/config")
    public Object getMeshConfig() {
        return resource.config();
    }

    @PlatformQuery("List mesh instances")
    @RestPath("/instances")
    public Object listInstances() {
        return resource.instances();
    }

    // postMessage deferred — MeshResource.PostMessageRequest is package-private
    // TODO: make PostMessageRequest public or extract to top-level record
}
