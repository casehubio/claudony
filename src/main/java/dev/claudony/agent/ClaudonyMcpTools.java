package dev.claudony.agent;

import dev.claudony.agent.terminal.TerminalAdapterFactory;
import dev.claudony.config.ClaudonyConfig;
import dev.claudony.server.model.CreateSessionRequest;
import dev.claudony.server.model.SendInputRequest;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Claudony's 8 MCP tools, registered via quarkus-mcp-server-http.
 *
 * <p>
 * quarkus-mcp-server handles the JSON-RPC 2.0 protocol (initialize, tools/list,
 * tools/call, notifications/initialized) at {@code /mcp}. Each {@code @Tool} method
 * returns a String that the library wraps as a text content item.
 *
 * <p>
 * Migrated from hand-rolled {@code McpServer.java} (deleted) to enable the
 * quarkus-qhorus embedding — once Qhorus is added as a dependency its 38 tools
 * auto-register on the same /mcp endpoint alongside these 8.
 */
@ApplicationScoped
public class ClaudonyMcpTools {

    @Inject
    ClaudonyConfig config;

    @RestClient
    ServerClient server;

    @Inject
    TerminalAdapterFactory terminalFactory;

    @Tool(name = "list_sessions", description = "List all active Claude Code sessions")
    public String listSessions() {
        final var sessions = server.listSessions();
        if (sessions.isEmpty()) {
            return "No active sessions.";
        }
        return sessions.stream()
                .map(s -> "• %s (id=%s, status=%s, dir=%s)"
                        .formatted(s.name(), s.id(), s.status(), s.workingDir()))
                .reduce("Sessions:\n", (a, b) -> a + "\n" + b);
    }

    @Tool(name = "create_session", description = "Create a new Claude Code session")
    public String createSession(
            @ToolArg(name = "name", description = "Session name") String name,
            @ToolArg(name = "workingDir", description = "Working directory") String workingDir,
            @ToolArg(name = "command", description = "Shell command to run (optional)", required = false) String command) {
        final var req = new CreateSessionRequest(
                name,
                workingDir,
                (command != null && !command.isBlank()) ? command : null);
        final var s = server.createSession(req);
        return "Created '%s' (id=%s)\nBrowser: %s".formatted(s.name(), s.id(), s.browserUrl());
    }

    @Tool(name = "delete_session", description = "Delete a session by id")
    public String deleteSession(
            @ToolArg(name = "id", description = "Session id") String id) {
        server.deleteSession(id);
        return "Session deleted.";
    }

    @Tool(name = "rename_session", description = "Rename a session")
    public String renameSession(
            @ToolArg(name = "id", description = "Session id") String id,
            @ToolArg(name = "name", description = "New session name") String name) {
        final var s = server.renameSession(id, name);
        return "Renamed to '%s'.".formatted(s.name());
    }

    @Tool(name = "send_input", description = "Send text input to a session")
    public String sendInput(
            @ToolArg(name = "id", description = "Session id") String id,
            @ToolArg(name = "text", description = "Text to send") String text) {
        server.sendInput(id, new SendInputRequest(text));
        return "Input sent.";
    }

    @Tool(name = "get_output", description = "Get recent terminal output from a session")
    public String getOutput(
            @ToolArg(name = "id", description = "Session id") String id,
            @ToolArg(name = "lines", description = "Number of lines to return (default 50)", required = false) Integer lines) {
        return server.getOutput(id, lines != null ? lines : 50);
    }

    @Tool(name = "open_in_terminal", description = "Open a session in a local terminal window")
    public String openInTerminal(
            @ToolArg(name = "id", description = "Session id") String id) {
        final var adapter = terminalFactory.resolve();
        if (adapter.isEmpty()) {
            return "No terminal adapter available on this machine.";
        }
        final var sessions = server.listSessions();
        final var session = sessions.stream()
                .filter(s -> s.id().equals(id))
                .findFirst();
        if (session.isEmpty()) {
            return "Session not found.";
        }
        try {
            adapter.get().openSession(session.get().name());
        } catch (final java.io.IOException | InterruptedException e) {
            return "Failed to open terminal: " + e.getMessage();
        }
        return "Opened in %s.".formatted(adapter.get().name());
    }

    @Tool(name = "get_server_info", description = "Get server connection info and status")
    public String getServerInfo() {
        final var adapter = terminalFactory.resolve();
        return "Server URL: %s\nAgent mode: %s\nTerminal adapter: %s".formatted(
                config.serverUrl(),
                config.mode(),
                adapter.map(a -> a.name()).orElse("none"));
    }
}
