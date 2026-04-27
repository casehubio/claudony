package dev.claudony.agent;

import static io.restassured.RestAssured.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import dev.claudony.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Integration tests for the MCP server using the real ServerClient (no mocks).
 *
 * <p>Each test gets a fresh MCP session from {@code @BeforeEach} so failures are
 * isolated. Any tmux session created during a test is cleaned up in {@code @AfterEach}.
 *
 * <p>In test mode Quarkus starts on port 8081. The ServerClient URL is configured to
 * {@code http://localhost:8081} in test application.properties, so MCP tool calls proxy
 * back to the same running instance — testing the full MCP → REST → tmux chain.
 */
@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class McpServerIntegrationTest {

    private String sessionId;     // MCP protocol session, fresh per test
    private String tmuxSessionId; // tmux session created during a test, cleaned up after

    @BeforeEach
    void initMcpSession() {
        var response = given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""
                {"jsonrpc":"2.0","id":0,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05","capabilities":{},
                           "clientInfo":{"name":"test","version":"1"}}}
                """)
            .when().post("/mcp")
            .then().statusCode(200).extract().response();
        sessionId = response.header("Mcp-Session-Id");
    }

    @AfterEach
    void cleanupTmuxSession() {
        if (tmuxSessionId != null) {
            mcp().body(deleteBody(tmuxSessionId)).when().post("/mcp");
            tmuxSessionId = null;
        }
    }

    private RequestSpecification mcp() {
        return given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .header("Mcp-Session-Id", sessionId);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void fullSessionLifecycle_createSendReceiveDelete() {
        // Create
        var text = mcp()
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"create_session",
                           "arguments":{"name":"mcp-lifecycle",
                                        "workingDir":"/tmp",
                                        "command":"echo mcp-ok"}}}
                """)
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", containsString("claudony-mcp-lifecycle"))
            .extract().<String>path("result.content[0].text");

        tmuxSessionId = extractSessionId(text);
        assertThat(tmuxSessionId).as("session ID extracted from create_session response").isNotNull();

        // Send input
        mcp().body(sendInputBody(tmuxSessionId, "echo mcp-input-marker\\n"))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", equalTo("Input sent."));

        // Get output
        Await.until(() -> {
            var out = mcp().body(getOutputBody(tmuxSessionId, 20))
                .when().post("/mcp")
                .then().statusCode(200)
                .extract().<String>path("result.content[0].text");
            return out != null && out.contains("mcp-input-marker");
        }, "'mcp-input-marker' to appear in session output");

        // Delete (also done by @AfterEach as safety net)
        mcp().body(deleteBody(tmuxSessionId))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", equalTo("Session deleted."));
        tmuxSessionId = null;
    }

    @Test
    void renameSession_updatesNameThroughFullChain() {
        var text = mcp()
            .body(createBody("mcp-rename-test", "/tmp", "bash"))
            .when().post("/mcp")
            .then().statusCode(200)
            .extract().<String>path("result.content[0].text");

        tmuxSessionId = extractSessionId(text);
        assertThat(tmuxSessionId).as("session ID extracted from create_session response").isNotNull();

        mcp().body("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"rename_session",
                           "arguments":{"id":"%s","name":"mcp-renamed"}}}
                """.formatted(tmuxSessionId))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", containsString("claudony-mcp-renamed"));
    }

    @Test
    void inputWithTmuxKeyName_appearsAsLiteralText() {
        var text = mcp()
            .body(createBody("mcp-keyname-test", "/tmp", "bash"))
            .when().post("/mcp")
            .then().statusCode(200)
            .extract().<String>path("result.content[0].text");

        tmuxSessionId = extractSessionId(text);
        assertThat(tmuxSessionId).as("session ID extracted from create_session response").isNotNull();
        Await.until(() -> {
            var t = mcp().body(getOutputBody(tmuxSessionId, 5))
                .when().post("/mcp")
                .then().statusCode(200)
                .extract().<String>path("result.content[0].text");
            return t != null && !t.isBlank();
        }, "bash prompt to appear after session creation");

        mcp().body(sendInputBody(tmuxSessionId, "Escape"))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", equalTo("Input sent."));

        Await.until(() -> {
            var t = mcp().body(getOutputBody(tmuxSessionId, 20))
                .when().post("/mcp")
                .then().statusCode(200)
                .extract().<String>path("result.content[0].text");
            return t != null && t.contains("Escape");
        }, "'Escape' to appear in session output");
    }

    @Test
    void getServerInfo_returnsExpectedFields() {
        mcp()
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"get_server_info","arguments":{}}}
                """)
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", containsString("Server URL:"))
            .body("result.content[0].text", containsString("Agent mode:"));
    }

    @Test
    void deleteSession_nonExistentId_returnsReadableErrorMessage() {
        // Call delete_session with a UUID that doesn't correspond to any real session.
        // With error handling in place, the tool returns a readable string — not an exception.
        var fakeId = "00000000-0000-0000-0000-000000000000";
        var result = mcp()
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"delete_session","arguments":{"id":"%s"}}}
                """.formatted(fakeId))
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].text", notNullValue())
            .extract().<String>path("result.content[0].text");

        // Tool returns a readable error string, not a JSON-RPC error and not an exception.
        assertThat(result)
            .as("delete of non-existent session should return readable error")
            .containsIgnoringCase("not found")
            .doesNotContain("Exception");
    }

    @Test
    void fullHandshakeSequence_asClaudeWouldSendIt() {
        var initResponse = given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05","capabilities":{},
                           "clientInfo":{"name":"claude","version":"1"}}}
                """)
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.serverInfo.name", equalTo("claudony"))
            .extract().response();

        var freshSid = initResponse.header("Mcp-Session-Id");

        // notifications/initialized
        given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .header("Mcp-Session-Id", freshSid)
            .body("""
                {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
                """)
            .when().post("/mcp")
            .then().statusCode(anyOf(equalTo(200), equalTo(202), equalTo(204)));

        // tools/list
        given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .header("Mcp-Session-Id", freshSid)
            .body("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """)
            .when().post("/mcp")
            .then().statusCode(200)
            // 8 Claudony tools + 41 Qhorus tools = 49 total at the unified /mcp endpoint
            .body("result.tools.size()", greaterThanOrEqualTo(8))
            .body("result.tools.name", hasItems(
                "list_sessions", "create_session", "delete_session",
                "rename_session", "send_input", "get_output",
                "open_in_terminal", "get_server_info"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractSessionId(String text) {
        var m = java.util.regex.Pattern.compile("/app/session/([\\w-]+)").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String createBody(String name, String dir, String cmd) {
        return """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"create_session",
                       "arguments":{"name":"%s","workingDir":"%s","command":"%s"}}}
            """.formatted(name, dir, cmd);
    }

    private String sendInputBody(String id, String text) {
        return """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"send_input","arguments":{"id":"%s","text":"%s"}}}
            """.formatted(id, text);
    }

    private String getOutputBody(String id, int lines) {
        return """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"get_output","arguments":{"id":"%s","lines":%d}}}
            """.formatted(id, lines);
    }

    private String deleteBody(String id) {
        return """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"delete_session","arguments":{"id":"%s"}}}
            """.formatted(id);
    }

    @Test
    void toolsList_includesQhorusTools() {
        var initResponse = given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05","capabilities":{},
                           "clientInfo":{"name":"test","version":"1"}}}
                """)
            .when().post("/mcp")
            .then().statusCode(200).extract().response();

        var sid = initResponse.header("Mcp-Session-Id");

        given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .header("Mcp-Session-Id", sid)
            .body("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """)
            .when().post("/mcp")
            .then()
            .statusCode(200)
            // Claudony's 8 tools
            .body("result.tools.name", hasItems("list_sessions", "get_server_info"))
            // Key Qhorus tools — confirms Qhorus embedding is working
            .body("result.tools.name", hasItems(
                "send_message", "check_messages", "register",
                "create_channel", "list_ledger_entries", "get_channel_timeline"))
            // 50 total: 8 Claudony + 42 Qhorus (count updated as Qhorus evolves)
            .body("result.tools.size()", equalTo(50));
    }
}

    // -------------------------------------------------------------------------
    // Phase 8 — Qhorus tools present at unified /mcp endpoint
    // -------------------------------------------------------------------------

