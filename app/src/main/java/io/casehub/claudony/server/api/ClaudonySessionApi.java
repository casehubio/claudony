package io.casehub.claudony.server.api;

import io.casehub.claudony.agent.terminal.TerminalAdapterFactory;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.SessionService;
import io.casehub.claudony.server.model.CreateSessionRequest;
import io.casehub.claudony.server.model.GitStatusResponse;
import io.casehub.claudony.server.model.PortStatus;
import io.casehub.claudony.server.model.SendInputRequest;
import io.casehub.claudony.server.model.SessionResponse;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.QueryParam;

import java.util.List;

@McpDomain(value = "claudony/sessions", basePath = "/api/claudony/sessions")
@ApplicationScoped
public class ClaudonySessionApi {

    @Inject SessionService sessionService;
    @Inject TerminalAdapterFactory terminalFactory;

    @PlatformQuery("List all sessions")
    @RestPath("/")
    public List<SessionResponse> listSessions(@QueryParam("localOnly") Boolean localOnly,
                                               @QueryParam("caseId") String caseId) {
        return sessionService.listSessions(localOnly != null && localOnly, caseId);
    }

    @PlatformQuery("Get session details")
    @RestPath("/{id}")
    public SessionResponse getSession(@PathParam String id) {
        return sessionService.getSession(id);
    }

    @PlatformQuery("Get session lineage")
    @RestPath("/{id}/lineage")
    public List<?> getLineage(@PathParam String id) {
        return sessionService.getLineage(id);
    }

    @PlatformMutation("Create a new session")
    @RestPath("/")
    public SessionResponse createSession(CreateSessionRequest request,
                                          @QueryParam("overwrite") Boolean overwrite) {
        return sessionService.createSession(request, overwrite != null && overwrite);
    }

    @PlatformMutation("Delete a session")
    @RestPath("/{id}/delete")
    public void deleteSession(@PathParam String id) {
        sessionService.deleteSession(id);
    }

    @PlatformMutation("Rename a session")
    @RestPath("/{id}/rename")
    public SessionResponse renameSession(@PathParam String id, String name) {
        return sessionService.renameSession(id, name);
    }

    @PlatformMutation("Send input to a session")
    @RestPath("/{id}/input")
    public void sendInput(@PathParam String id, SendInputRequest request) {
        sessionService.sendInput(id, request);
    }

    @PlatformQuery("Get session output")
    @RestPath("/{id}/output")
    public String getOutput(@PathParam String id, @QueryParam("lines") Integer lines) {
        return sessionService.getOutput(id, lines != null ? lines : 50);
    }

    @PlatformMutation("Resize session terminal")
    @RestPath("/{id}/resize")
    public void resize(@PathParam String id,
                        @QueryParam("cols") Integer cols,
                        @QueryParam("rows") Integer rows) {
        sessionService.resize(id, cols != null ? cols : 80, rows != null ? rows : 24);
    }

    @PlatformMutation("Open session in native terminal")
    @RestPath("/{id}/open-terminal")
    public OpenTerminalResult openInTerminal(@PathParam String id) {
        sessionService.openTerminal(id);
        return new OpenTerminalResult(true, terminalFactory.resolve().map(a -> a.name()).orElse("unknown"));
    }

    @PlatformQuery("Get git status for a session")
    @RestPath("/{id}/git-status")
    public GitStatusResponse getGitStatus(@PathParam String id) {
        return sessionService.getGitStatus(id);
    }

    @PlatformQuery("Get service health for a session")
    @RestPath("/{id}/service-health")
    public List<PortStatus> getServiceHealth(@PathParam String id) {
        return sessionService.getServiceHealth(id);
    }

    public record OpenTerminalResult(boolean opened, String adapter) {}
}
