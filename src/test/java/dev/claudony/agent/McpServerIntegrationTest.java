package dev.claudony.agent;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Integration test for the MCP server using the real ServerClient (no mocks).
 *
 * <p>
 * In test mode, Quarkus starts on port 8081. The ServerClient is configured
 * via quarkus.rest-client.claudony-server.url=http://localhost:8081 in test
 * application.properties, so MCP tool calls proxy back to the same running
 * instance — testing the full MCP → REST → tmux flow end-to-end.
 *
 * <p>
 * quarkus-mcp-server Streamable HTTP transport requires
 * {@code Accept: application/json, text/event-stream} on every request.
 * All requests use the {@code mcpPost()} helper to set this header.
 */
@QuarkusTest
@TestSecurity(user = "test", roles = "user")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServerIntegrationTest {

    private static String createdSessionId;

    /** Shared across the ordered tests; captured from initialize response. */
    private static String mcpSessionId;

    private io.restassured.specification.RequestSpecification mcpPost() {
        final var spec = given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream");
        if (mcpSessionId != null) {
            spec.header("Mcp-Session-Id", mcpSessionId);
        }
        return spec;
    }

    @Test
    @Order(1)
    void initializeHandshakeSucceeds() {
        final var response = mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize",
                         "params":{"protocolVersion":"2024-11-05",
                                   "capabilities":{},
                                   "clientInfo":{"name":"test","version":"1"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.protocolVersion", notNullValue())
                .body("result.serverInfo.name", equalTo("claudony"))
                .extract().response();

        mcpSessionId = response.header("Mcp-Session-Id");
    }

    @Test
    @Order(2)
    void createSessionViaMcpCreatesRealTmuxSession() {
        final var response = mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call",
                         "params":{"name":"create_session",
                                   "arguments":{"name":"mcp-integration",
                                                "workingDir":"/tmp",
                                                "command":"echo mcp-ok"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].type", equalTo("text"))
                .body("result.content[0].text", containsString("claudony-mcp-integration"))
                .extract().response();

        final var text = response.path("result.content[0].text").toString();
        final var parts = text.split("Browser: http://localhost:\\d+/app/session/");
        if (parts.length > 1) {
            createdSessionId = parts[1].trim();
        }
    }

    @Test
    @Order(3)
    void listSessionsViaMcpShowsCreatedSession() {
        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":3,"method":"tools/call",
                         "params":{"name":"list_sessions","arguments":{}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", containsString("claudony-mcp-integration"));
    }

    @Test
    @Order(4)
    void sendInputViaMcpWritesToSession() {
        Assumptions.assumeTrue(createdSessionId != null, "Session ID not captured");

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":4,"method":"tools/call",
                         "params":{"name":"send_input",
                                   "arguments":{"id":"%s","text":"echo mcp-input-marker\\n"}}}
                        """.formatted(createdSessionId))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", equalTo("Input sent."));
    }

    @Test
    @Order(5)
    void getOutputViaMcpReturnsTerminalContent() throws Exception {
        Assumptions.assumeTrue(createdSessionId != null, "Session ID not captured");
        Thread.sleep(300);

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":5,"method":"tools/call",
                         "params":{"name":"get_output",
                                   "arguments":{"id":"%s","lines":20}}}
                        """.formatted(createdSessionId))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", containsString("mcp-input-marker"));
    }

    @Test
    @Order(6)
    void deleteSessionViaMcpKillsTmuxSession() {
        Assumptions.assumeTrue(createdSessionId != null, "Session ID not captured");

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":6,"method":"tools/call",
                         "params":{"name":"delete_session",
                                   "arguments":{"id":"%s"}}}
                        """.formatted(createdSessionId))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", equalTo("Session deleted."));
    }

    @Test
    @Order(7)
    void getServerInfoReturnsExpectedFields() {
        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":7,"method":"tools/call",
                         "params":{"name":"get_server_info","arguments":{}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", containsString("Server URL:"))
                .body("result.content[0].text", containsString("Agent mode:"));
    }

    @Test
    @Order(8)
    void toolsListReturnsAll8ToolsWithCorrectNames() {
        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":8,"method":"tools/list","params":{}}
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

    @Test
    @Order(9)
    void unknownToolNameReturnsError() {
        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":9,"method":"tools/call",
                         "params":{"name":"nonexistent_tool","arguments":{}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("error", notNullValue());
    }

    @Test
    @Order(10)
    void renameSessionViaMcpUpdatesNameThroughFullChain() {
        final var createText = mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":10,"method":"tools/call",
                         "params":{"name":"create_session",
                                   "arguments":{"name":"mcp-rename-test",
                                                "workingDir":"/tmp",
                                                "command":"bash"}}}
                        """)
                .when().post("/mcp")
                .then().statusCode(200).extract().<String>path("result.content[0].text");

        final var parts = createText.split("Browser: http://localhost:\\d+/app/session/");
        Assumptions.assumeTrue(parts.length > 1, "Could not extract session ID");
        final var sid = parts[1].trim();

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":11,"method":"tools/call",
                         "params":{"name":"rename_session",
                                   "arguments":{"id":"%s","name":"mcp-renamed"}}}
                        """.formatted(sid))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", containsString("claudony-mcp-renamed"));

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":12,"method":"tools/call",
                         "params":{"name":"delete_session","arguments":{"id":"%s"}}}
                        """.formatted(sid))
                .when().post("/mcp");
    }

    @Test
    @Order(11)
    void fullLifecycleWithTmuxKeyNameInInputPreservesLiteralText() throws Exception {
        final var createText = mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":13,"method":"tools/call",
                         "params":{"name":"create_session",
                                   "arguments":{"name":"mcp-keyname-test",
                                                "workingDir":"/tmp",
                                                "command":"bash"}}}
                        """)
                .when().post("/mcp")
                .then().statusCode(200).extract().<String>path("result.content[0].text");

        final var parts = createText.split("Browser: http://localhost:\\d+/app/session/");
        Assumptions.assumeTrue(parts.length > 1, "Could not extract session ID");
        final var sid = parts[1].trim();
        Thread.sleep(300);

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":14,"method":"tools/call",
                         "params":{"name":"send_input","arguments":{"id":"%s","text":"Escape"}}}
                        """.formatted(sid))
                .when().post("/mcp")
                .then().statusCode(200).body("result.content[0].text", equalTo("Input sent."));

        Thread.sleep(300);

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":15,"method":"tools/call",
                         "params":{"name":"get_output","arguments":{"id":"%s","lines":20}}}
                        """.formatted(sid))
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.content[0].text", containsString("Escape"));

        mcpPost()
                .body("""
                        {"jsonrpc":"2.0","id":16,"method":"tools/call",
                         "params":{"name":"delete_session","arguments":{"id":"%s"}}}
                        """.formatted(sid))
                .when().post("/mcp");
    }

    @Test
    @Order(12)
    void fullMcpHandshakeSequenceAsClaudeWouldSendIt() {
        // Start a fresh session to simulate Claude's full handshake
        final var initResponse = given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":17,"method":"initialize",
                         "params":{"protocolVersion":"2024-11-05",
                                   "capabilities":{},
                                   "clientInfo":{"name":"claude","version":"1"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.protocolVersion", notNullValue())
                .body("result.serverInfo.name", equalTo("claudony"))
                .extract().response();

        final var freshSessionId = initResponse.header("Mcp-Session-Id");

        // notifications/initialized with the fresh session ID
        given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .header("Mcp-Session-Id", freshSessionId)
                .body("""
                        {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(anyOf(equalTo(200), equalTo(202), equalTo(204)));

        given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .header("Mcp-Session-Id", freshSessionId)
                .body("""
                        {"jsonrpc":"2.0","id":18,"method":"tools/list","params":{}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("result.tools", hasSize(8));
    }
}
