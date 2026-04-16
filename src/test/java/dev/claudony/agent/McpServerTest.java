package dev.claudony.agent;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.Mockito;

import dev.claudony.agent.terminal.TerminalAdapterFactory;
import dev.claudony.server.model.SendInputRequest;
import dev.claudony.server.model.SessionResponse;
import dev.claudony.server.model.SessionStatus;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Unit tests for Claudony's 8 MCP tools via the quarkus-mcp-server HTTP endpoint.
 *
 * <p>
 * quarkus-mcp-server Streamable HTTP transport (MCP 2025-06-18) requires:
 * <ul>
 *   <li>{@code Accept: application/json, text/event-stream} on every request</li>
 *   <li>An {@code initialize} handshake before any other message; the server
 *       returns a {@code Mcp-Session-Id} header that must be echoed on all
 *       subsequent requests within the same logical session</li>
 * </ul>
 * Each test uses a fresh session established in {@code @BeforeEach}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServerTest {

    @InjectMock
    @RestClient
    ServerClient serverClient;

    @InjectMock
    TerminalAdapterFactory terminalFactory;

    /** Captured from the initialize response; sent as header on subsequent requests. */
    private String mcpSessionId;

    @BeforeEach
    void resetMocksAndInitialise() {
        Mockito.reset(serverClient, terminalFactory);
        Mockito.when(terminalFactory.resolve()).thenReturn(Optional.empty());

        // Establish a fresh MCP session — quarkus-mcp-server requires initialize
        // before any other message and returns a session ID that tracks state.
        final var response = given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":0,"method":"initialize",
                         "params":{"protocolVersion":"2024-11-05",
                                   "capabilities":{},
                                   "clientInfo":{"name":"test","version":"1"}}}
                        """)
                .when().post("/mcp")
                .then().statusCode(200).extract().response();

        mcpSessionId = response.header("Mcp-Session-Id");
    }

    // -------------------------------------------------------------------------
    // Helper — sets required Streamable HTTP headers for the current session
    // -------------------------------------------------------------------------

    private RequestSpecification mcpPost() {
        final var spec = given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream");
        if (mcpSessionId != null) {
            spec.header("Mcp-Session-Id", mcpSessionId);
        }
        return spec;
    }

    // -------------------------------------------------------------------------
    // Protocol handshake
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void initializeHandshakeReturns200() {
        // initialize already done in @BeforeEach — verify shape of a fresh one
        given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize",
                         "params":{"protocolVersion":"2024-11-05",
                                   "capabilities":{},
                                   "clientInfo":{"name":"test","version":"1"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("jsonrpc", equalTo("2.0"))
                .body("result.protocolVersion", notNullValue())
                .body("result.serverInfo.name", equalTo("claudony"));
    }

    // -------------------------------------------------------------------------
    // tools/list
    // -------------------------------------------------------------------------

    @Test
    @Order(2)
    void toolsListReturnsAll8Tools() {
        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.tools", hasSize(8))
                .body("result.tools.name", hasItems(
                        "list_sessions", "create_session", "delete_session",
                        "rename_session", "send_input", "get_output",
                        "open_in_terminal", "get_server_info"));
    }

    // -------------------------------------------------------------------------
    // Tool calls
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    void listSessionsToolProxiesToServer() {
        final var now = Instant.now();
        Mockito.when(serverClient.listSessions()).thenReturn(List.of(
                new SessionResponse("id-1", "claudony-proj", "/tmp", "claude",
                        SessionStatus.IDLE, now, now,
                        "ws://localhost:7777/ws/id-1",
                        "http://localhost:7777/app/session/id-1",
                        null, null, null)));

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call",
                         "params":{"name":"list_sessions","arguments":{}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].type", equalTo("text"))
                .body("result.content[0].text", containsString("claudony-proj"));
    }

    @Test
    @Order(4)
    void createSessionToolProxiesToServer() {
        final var now = Instant.now();
        Mockito.when(serverClient.createSession(Mockito.any())).thenReturn(
                new SessionResponse("id-2", "claudony-new", "/home", "claude",
                        SessionStatus.IDLE, now, now,
                        "ws://localhost:7777/ws/id-2",
                        "http://localhost:7777/app/session/id-2",
                        null, null, null));

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":3,"method":"tools/call",
                         "params":{"name":"create_session",
                                   "arguments":{"name":"new","workingDir":"/home"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", containsString("id-2"));
    }

    @Test
    @Order(5)
    void unknownMethodReturnsJsonRpcError() {
        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":9,"method":"nonexistent","params":{}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("error.code", equalTo(-32601));
    }

    @Test
    @Order(6)
    void serverErrorOnToolCallReturnsError() {
        Mockito.when(serverClient.createSession(Mockito.any()))
                .thenThrow(new RuntimeException("tmux: bad session name"));

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":6,"method":"tools/call",
                         "params":{"name":"create_session",
                                   "arguments":{"name":"bad","workingDir":"/tmp"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                // quarkus-mcp-server propagates tool exceptions as JSON-RPC errors
                .body("error", notNullValue());
    }

    @Test
    @Order(7)
    void sendInputPassesTextLiterallyToServer() {
        Mockito.doNothing().when(serverClient)
                .sendInput(Mockito.anyString(), Mockito.any(SendInputRequest.class));

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":7,"method":"tools/call",
                         "params":{"name":"send_input",
                                   "arguments":{"id":"id-1","text":"echo Escape marker\\n"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", equalTo("Input sent."));

        Mockito.verify(serverClient).sendInput(
                Mockito.eq("id-1"),
                Mockito.argThat(req -> "echo Escape marker\n".equals(req.text())));
    }

    @Test
    @Order(8)
    void renameSessionToolReturnsNewName() {
        final var now = Instant.now();
        Mockito.when(serverClient.renameSession(
                Mockito.eq("id-1"), Mockito.eq("newname")))
                .thenReturn(new SessionResponse("id-1", "claudony-newname", "/tmp", "claude",
                        SessionStatus.IDLE, now, now,
                        "ws://localhost:7777/ws/id-1",
                        "http://localhost:7777/app/session/id-1",
                        null, null, null));

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":8,"method":"tools/call",
                         "params":{"name":"rename_session",
                                   "arguments":{"id":"id-1","name":"newname"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", containsString("claudony-newname"));
    }

    @Test
    @Order(9)
    void openInTerminalWithNoAdapterReturnsHelpfulMessage() {
        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":9,"method":"tools/call",
                         "params":{"name":"open_in_terminal","arguments":{"id":"id-1"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text",
                        equalTo("No terminal adapter available on this machine."));
    }

    @Test
    @Order(10)
    void getServerInfoReturnsExpectedFields() {
        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":10,"method":"tools/call",
                         "params":{"name":"get_server_info","arguments":{}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", containsString("Server URL:"))
                .body("result.content[0].text", containsString("Agent mode:"))
                .body("result.content[0].text", containsString("Terminal adapter: none"));
    }
}
