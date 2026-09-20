package io.casehub.claudony.server.api;

import io.casehub.claudony.server.SessionResource;
import io.casehub.claudony.server.model.CreateSessionRequest;
import io.casehub.claudony.server.model.SendInputRequest;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.QueryParam;

@McpDomain(value = "claudony/sessions", basePath = "/api/claudony/sessions")
@ApplicationScoped
public class ClaudonySessionApi {

    @Inject SessionResource resource;

    @PlatformQuery("List all sessions")
    @RestPath("/")
    public Object listSessions(@QueryParam("localOnly") Boolean localOnly,
                                @QueryParam("caseId") String caseId) {
        return resource.list(localOnly, caseId);
    }

    @PlatformQuery("Get session details")
    @RestPath("/{id}")
    public Object getSession(@PathParam String id) {
        return resource.get(id).getEntity();
    }

    @PlatformQuery("Get session lineage")
    @RestPath("/{id}/lineage")
    public Object getLineage(@PathParam String id) {
        return resource.getLineage(id).getEntity();
    }

    @PlatformMutation("Create a new session")
    @RestPath("/")
    public Object createSession(CreateSessionRequest request,
                                 @QueryParam("overwrite") Boolean overwrite) {
        return resource.create(request, overwrite).getEntity();
    }

    @PlatformMutation("Delete a session")
    @RestPath("/{id}/delete")
    public Object deleteSession(@PathParam String id) {
        return resource.delete(id).getEntity();
    }

    @PlatformMutation("Rename a session")
    @RestPath("/{id}/rename")
    public Object renameSession(@PathParam String id, String name) {
        return resource.rename(id, name).getEntity();
    }

    @PlatformMutation("Send input to a session")
    @RestPath("/{id}/input")
    public Object sendInput(@PathParam String id, SendInputRequest request) {
        return resource.sendInput(id, request).getEntity();
    }

    @PlatformQuery("Get session output")
    @RestPath("/{id}/output")
    public Object getOutput(@PathParam String id, @QueryParam("lines") Integer lines) {
        return resource.getOutput(id, lines).getEntity();
    }

    @PlatformMutation("Resize session terminal")
    @RestPath("/{id}/resize")
    public Object resize(@PathParam String id,
                          @QueryParam("cols") Integer cols,
                          @QueryParam("rows") Integer rows) {
        return resource.resize(id, cols, rows).getEntity();
    }

    @PlatformMutation("Open session in native terminal")
    @RestPath("/{id}/open-terminal")
    public Object openInTerminal(@PathParam String id) {
        return resource.openTerminal(id).getEntity();
    }

    @PlatformQuery("Get git status for a session")
    @RestPath("/{id}/git-status")
    public Object getGitStatus(@PathParam String id) {
        return resource.gitStatus(id).getEntity();
    }

    @PlatformQuery("Get service health for a session")
    @RestPath("/{id}/service-health")
    public Object getServiceHealth(@PathParam String id) {
        return resource.serviceHealth(id).getEntity();
    }
}
