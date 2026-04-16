package dev.claudony.agent;

import static io.restassured.RestAssured.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Protocol compliance tests for the quarkus-mcp-server Streamable HTTP endpoint.
 *
 * <p>Tests the transport layer behaviour — Accept headers, session ID enforcement,
 * error codes — not tool logic. Tool logic lives in ClaudonyMcpToolsTest.
 */
@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class McpProtocolTest {

    private RequestSpecification mcp(String sessionId) {
        var spec = given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream");
        if (sessionId != null) spec.header("Mcp-Session-Id", sessionId);
        return spec;
    }

    private String initialize() {
        return given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""
                {"jsonrpc":"2.0","id":0,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05","capabilities":{},
                           "clientInfo":{"name":"test","version":"1"}}}
                """)
            .when().post("/mcp")
            .then().statusCode(200)
            .extract().header("Mcp-Session-Id");
    }

    @Test
    void post_withoutCorrectAcceptHeader_returns400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """)
            .when().post("/mcp")
            .then().statusCode(400);
    }

    @Test
    void initialize_returnsMcpSessionIdHeader() {
        assertThat(initialize()).isNotNull();
    }

    @Test
    void initialize_returnsClaudonyServerInfo() {
        given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""
                {"jsonrpc":"2.0","id":0,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05","capabilities":{},
                           "clientInfo":{"name":"test","version":"1"}}}
                """)
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.serverInfo.name", equalTo("claudony"))
            .body("result.protocolVersion", notNullValue());
    }

    @Test
    void unknownMethod_returns32601() {
        var sid = initialize();
        mcp(sid)
            .body("""
                {"jsonrpc":"2.0","id":9,"method":"nonexistent","params":{}}
                """)
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("error.code", equalTo(-32601));
    }

    @Test
    void toolsList_withValidSession_returnsAll8ClaudonyTools() {
        var sid = initialize();
        mcp(sid)
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """)
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.tools.name", hasItems(
                "list_sessions", "create_session", "delete_session",
                "rename_session", "send_input", "get_output",
                "open_in_terminal", "get_server_info"));
    }
}
