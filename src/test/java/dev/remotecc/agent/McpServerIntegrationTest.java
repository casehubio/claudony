package dev.remotecc.agent;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for the MCP server using the real ServerClient (no mocks).
 *
 * In test mode, Quarkus starts on port 8081. The ServerClient is configured
 * via quarkus.rest-client.remotecc-server.url=http://localhost:8081 in test
 * application.properties, so MCP tool calls proxy back to the same running
 * instance — testing the full MCP → REST → tmux flow end-to-end.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServerIntegrationTest {

    private static String createdSessionId;

    @Test
    @Order(1)
    void initializeHandshakeSucceeds() {
        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.protocolVersion", equalTo("2024-11-05"))
            .body("result.serverInfo.name", equalTo("remotecc"));
    }

    @Test
    @Order(2)
    void createSessionViaMcpCreatesRealTmuxSession() {
        var response = given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"create_session\",\"arguments\":{\"name\":\"mcp-integration\",\"workingDir\":\"/tmp\",\"command\":\"echo mcp-ok\"}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].type", equalTo("text"))
            .body("result.content[0].text", containsString("remotecc-mcp-integration"))
            .extract().response();

        // Extract session id from browser URL in response text
        var text = response.path("result.content[0].text").toString();
        var parts = text.split("Browser: http://localhost:\\d+/app/session/");
        if (parts.length > 1) {
            createdSessionId = parts[1].trim();
        }
    }

    @Test
    @Order(3)
    void listSessionsViaMcpShowsCreatedSession() {
        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"list_sessions\",\"arguments\":{}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].text", containsString("remotecc-mcp-integration"));
    }

    @Test
    @Order(4)
    void sendInputViaMcpWritesToSession() {
        Assumptions.assumeTrue(createdSessionId != null, "Session ID not captured from previous test");

        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"send_input\",\"arguments\":{\"id\":\"" + createdSessionId + "\",\"text\":\"echo mcp-input-marker\\n\"}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].text", equalTo("Input sent."));
    }

    @Test
    @Order(5)
    void getOutputViaMcpReturnsTerminalContent() throws Exception {
        Assumptions.assumeTrue(createdSessionId != null, "Session ID not captured from previous test");
        Thread.sleep(300);

        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_output\",\"arguments\":{\"id\":\"" + createdSessionId + "\",\"lines\":20}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].text", containsString("mcp-input-marker"));
    }

    @Test
    @Order(6)
    void deleteSessionViaMcpKillsTmuxSession() {
        Assumptions.assumeTrue(createdSessionId != null, "Session ID not captured from previous test");

        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"delete_session\",\"arguments\":{\"id\":\"" + createdSessionId + "\"}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].text", equalTo("Session deleted."));
    }

    @Test
    @Order(7)
    void getServerInfoReturnsExpectedFields() {
        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"get_server_info\",\"arguments\":{}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].text", containsString("Server URL:"))
            .body("result.content[0].text", containsString("Agent mode:"));
    }
}
