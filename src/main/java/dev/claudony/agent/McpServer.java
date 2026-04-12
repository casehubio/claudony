package dev.claudony.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import dev.claudony.agent.terminal.TerminalAdapterFactory;
import dev.claudony.config.ClaudonyConfig;
import dev.claudony.server.model.CreateSessionRequest;
import dev.claudony.server.model.SendInputRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@Path("/mcp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Inject ClaudonyConfig config;
    @RestClient ServerClient server;
    @Inject TerminalAdapterFactory terminalFactory;

    @POST
    public Response handle(JsonNode request) {
        var id = request.get("id");
        var method = request.path("method").asText("");

        try {
            return switch (method) {
                case "initialize" -> ok(id, initialize());
                case "tools/list" -> ok(id, toolsList());
                case "tools/call" -> ok(id, toolCall(request.path("params")));
                case "notifications/initialized" -> Response.noContent().build();
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            LOG.errorf("MCP error for '%s': %s", method, e.getMessage());
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private ObjectNode initialize() {
        var result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        ObjectNode capabilities = MAPPER.createObjectNode();
        capabilities.set("tools", MAPPER.createObjectNode());
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = MAPPER.createObjectNode();
        serverInfo.put("name", "remotecc");
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);
        return result;
    }

    private ObjectNode toolsList() {
        var tools = MAPPER.createArrayNode();
        tools.add(tool("list_sessions", "List all active Claude Code sessions",
                objectSchema()));
        tools.add(tool("create_session", "Create a new Claude Code session",
                schema("name", "workingDir", "command")));
        tools.add(tool("delete_session", "Delete a session by id", idSchema()));
        tools.add(tool("rename_session", "Rename a session", schema("id", "name")));
        tools.add(tool("send_input", "Send text input to a session", schema("id", "text")));
        tools.add(tool("get_output", "Get recent terminal output from a session",
                schemaWithInteger("id", "lines")));
        tools.add(tool("open_in_terminal", "Open a session in a local terminal window", idSchema()));
        tools.add(tool("get_server_info", "Get server connection info and status", objectSchema()));
        var result = MAPPER.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode schema(String... fields) {
        ObjectNode props = MAPPER.createObjectNode();
        for (String f : fields) {
            props.set(f, MAPPER.createObjectNode().put("type", "string"));
        }
        ObjectNode s = MAPPER.createObjectNode();
        s.put("type", "object");
        s.set("properties", props);
        return s;
    }

    private ObjectNode schemaWithInteger(String stringField, String intField) {
        ObjectNode props = MAPPER.createObjectNode();
        props.set(stringField, MAPPER.createObjectNode().put("type", "string"));
        props.set(intField, MAPPER.createObjectNode().put("type", "integer"));
        ObjectNode s = MAPPER.createObjectNode();
        s.put("type", "object");
        s.set("properties", props);
        return s;
    }

    private JsonNode toolCall(JsonNode params) throws Exception {
        var name = params.path("name").asText("");
        var args = params.path("arguments");

        var text = switch (name) {
            case "list_sessions" -> {
                var sessions = server.listSessions();
                yield sessions.isEmpty() ? "No active sessions."
                        : sessions.stream()
                                .map(s -> "• %s (id=%s, status=%s, dir=%s)"
                                        .formatted(s.name(), s.id(), s.status(), s.workingDir()))
                                .reduce("Sessions:\n", (a, b) -> a + "\n" + b);
            }
            case "create_session" -> {
                var req = new CreateSessionRequest(
                        args.path("name").asText(),
                        args.path("workingDir").asText(),
                        args.has("command") ? args.path("command").asText() : null);
                var s = server.createSession(req);
                yield "Created '%s' (id=%s)\nBrowser: %s".formatted(s.name(), s.id(), s.browserUrl());
            }
            case "delete_session" -> {
                server.deleteSession(args.path("id").asText());
                yield "Session deleted.";
            }
            case "rename_session" -> {
                var s = server.renameSession(args.path("id").asText(), args.path("name").asText());
                yield "Renamed to '%s'.".formatted(s.name());
            }
            case "send_input" -> {
                server.sendInput(args.path("id").asText(),
                        new SendInputRequest(args.path("text").asText()));
                yield "Input sent.";
            }
            case "get_output" -> {
                var lines = args.has("lines") ? args.path("lines").asInt() : 50;
                yield server.getOutput(args.path("id").asText(), lines);
            }
            case "open_in_terminal" -> {
                var adapter = terminalFactory.resolve();
                if (adapter.isEmpty()) yield "No terminal adapter available on this machine.";
                var sessions = server.listSessions();
                var session = sessions.stream()
                        .filter(s -> s.id().equals(args.path("id").asText()))
                        .findFirst();
                if (session.isEmpty()) yield "Session not found.";
                adapter.get().openSession(session.get().name());
                yield "Opened in %s.".formatted(adapter.get().name());
            }
            case "get_server_info" -> {
                var adapter = terminalFactory.resolve();
                yield "Server URL: %s\nAgent mode: %s\nTerminal adapter: %s".formatted(
                        config.serverUrl(), config.mode(),
                        adapter.map(a -> a.name()).orElse("none"));
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };

        var content = MAPPER.createArrayNode();
        content.add(MAPPER.createObjectNode().put("type", "text").put("text", text));
        var result = MAPPER.createObjectNode();
        result.set("content", content);
        return result;
    }

    private ObjectNode tool(String name, String description, JsonNode inputSchema) {
        return MAPPER.createObjectNode()
                .put("name", name).put("description", description)
                .set("inputSchema", inputSchema);
    }

    private ObjectNode idSchema() {
        return MAPPER.createObjectNode().put("type", "object")
                .set("properties", MAPPER.createObjectNode()
                        .set("id", MAPPER.createObjectNode().put("type", "string")));
    }

    private ObjectNode objectSchema() {
        return MAPPER.createObjectNode().put("type", "object")
                .set("properties", MAPPER.createObjectNode());
    }

    private Response ok(JsonNode id, Object result) throws Exception {
        var node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        if (id != null) node.set("id", id);
        node.set("result", MAPPER.valueToTree(result));
        return Response.ok(node).build();
    }

    private Response error(JsonNode id, int code, String message) {
        var node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        if (id != null) node.set("id", id);
        node.putObject("error").put("code", code).put("message", message);
        return Response.ok(node).build();
    }
}
