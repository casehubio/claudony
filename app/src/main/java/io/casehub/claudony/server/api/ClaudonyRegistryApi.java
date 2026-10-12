package io.casehub.claudony.server.api;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.platform.api.registry.CascadeResult;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.api.registry.Relationship;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.List;

@McpDomain(value = "claudony/registry", app = "claudony",
           basePath = "/api/claudony/registry",
           summary = "Service registry — registration, discovery, relationships, health")
@ApplicationScoped
public class ClaudonyRegistryApi {

    @Inject RegistryService registryService;

    @PlatformMutation("Register an entry")
    @RestPath("/")
    public void registerEntry(RegistryEntry entry) {
        registryService.register(entry);
    }

    @PlatformQuery("Resolve an entry by ID")
    @RestPath("/{id}")
    public RegistryEntry resolve(@PathParam String id) {
        return registryService.resolve(id)
                .orElseThrow(() -> new WebApplicationException(Response.Status.NOT_FOUND));
    }

    @PlatformMutation("Heartbeat an entry")
    @RestPath("/{id}/heartbeat")
    public void heartbeat(@PathParam String id) {
        registryService.heartbeat(id);
    }

    @PlatformMutation("Deregister an entry")
    @RestPath("/{id}/deregister")
    public CascadeResult deregisterEntry(@PathParam String id) {
        return registryService.deregister(id);
    }

    @PlatformMutation("Discover entries by query")
    @RestPath("/discover")
    public List<RegistryEntry> discover(RegistryQuery query) {
        return registryService.discover(query);
    }

    @PlatformMutation("Link two entries")
    @RestPath("/link")
    public void link(Relationship rel) {
        registryService.link(rel);
    }

    @PlatformMutation("Unlink two entries")
    @RestPath("/{sourceId}/unlink/{targetId}")
    public void unlink(@PathParam String sourceId, @PathParam String targetId) {
        registryService.unlink(sourceId, targetId);
    }

    @PlatformQuery("List relationships for an entry")
    @RestPath("/{id}/relationships")
    public List<Relationship> relationships(@PathParam String id) {
        return registryService.relationships(id);
    }
}
