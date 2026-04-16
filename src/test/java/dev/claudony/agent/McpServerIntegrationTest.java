package dev.claudony.agent;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

import dev.claudony.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
        Assumptions.assumeTrue(tmuxSessionId != null, "Could not extract session ID");

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
        Assumptions.assumeTrue(tmuxSessionId != null);

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
        Assumptions.assumeTrue(tmuxSessionId != null);
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
            .body("result.tools", hasSize(8));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractSessionId(String text) {
        var parts = text.split("Browser: http://localhost:\\d+/app/session/");
        return parts.length > 1 ? parts[1].trim() : null;
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
}
