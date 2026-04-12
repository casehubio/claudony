package dev.claudony.agent;

import dev.claudony.agent.terminal.TerminalAdapterFactory;
import dev.claudony.server.model.*;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpServerTest {

    @InjectMock
    @RestClient
    ServerClient serverClient;

    @InjectMock
    TerminalAdapterFactory terminalFactory;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(serverClient, terminalFactory);
        Mockito.when(terminalFactory.resolve()).thenReturn(Optional.empty());
    }

    @Test
    @Order(1)
    void initializeHandshakeReturns200() {
        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("jsonrpc", equalTo("2.0"))
            .body("result.protocolVersion", equalTo("2024-11-05"))
            .body("result.serverInfo.name", equalTo("remotecc"));
    }

    @Test
    @Order(2)
    void toolsListReturnsAll8Tools() {
        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")
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
    @Order(3)
    void listSessionsToolProxiesToServer() {
        var now = Instant.now();
        Mockito.when(serverClient.listSessions()).thenReturn(List.of(
            new SessionResponse("id-1", "remotecc-proj", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                "ws://localhost:7777/ws/id-1",
                "http://localhost:7777/app/session/id-1")
        ));

        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_sessions\",\"arguments\":{}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].type", equalTo("text"))
            .body("result.content[0].text", containsString("remotecc-proj"));
    }

    @Test
    @Order(4)
    void createSessionToolProxiesToServer() {
        var now = Instant.now();
        Mockito.when(serverClient.createSession(Mockito.any())).thenReturn(
            new SessionResponse("id-2", "remotecc-new", "/home", "claude",
                SessionStatus.IDLE, now, now,
                "ws://localhost:7777/ws/id-2",
                "http://localhost:7777/app/session/id-2")
        );

        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"create_session\",\"arguments\":{\"name\":\"new\",\"workingDir\":\"/home\"}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].text", containsString("id-2"));
    }

    @Test
    @Order(5)
    void unknownMethodReturnsJsonRpcError() {
        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"nonexistent\",\"params\":{}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("error.code", equalTo(-32601))
            .body("error.message", containsString("Method not found"));
    }

    @Test
    @Order(6)
    void serverErrorOnToolCallReturnsGracefulMcpError() {
        Mockito.when(serverClient.createSession(Mockito.any()))
            .thenThrow(new RuntimeException("tmux: bad session name"));

        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\"," +
                  "\"params\":{\"name\":\"create_session\"," +
                  "\"arguments\":{\"name\":\"bad\",\"workingDir\":\"/tmp\"}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("error.code", equalTo(-32603))
            .body("error.message", containsString("Internal error"));
    }

    @Test
    @Order(7)
    void sendInputPassesTextLiterallyToServer() {
        Mockito.doNothing().when(serverClient)
            .sendInput(Mockito.anyString(), Mockito.any(SendInputRequest.class));

        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\"," +
                  "\"params\":{\"name\":\"send_input\"," +
                  "\"arguments\":{\"id\":\"id-1\",\"text\":\"echo Escape marker\\n\"}}}")
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
        var now = Instant.now();
        Mockito.when(serverClient.renameSession(
                Mockito.eq("id-1"), Mockito.eq("newname")))
            .thenReturn(new SessionResponse("id-1", "remotecc-newname", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                "ws://localhost:7777/ws/id-1",
                "http://localhost:7777/app/session/id-1"));

        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\"," +
                  "\"params\":{\"name\":\"rename_session\"," +
                  "\"arguments\":{\"id\":\"id-1\",\"name\":\"newname\"}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].text", containsString("remotecc-newname"));
    }

    @Test
    @Order(9)
    void openInTerminalWithNoAdapterReturnsHelpfulMessage() {
        // terminalFactory.resolve() already returns Optional.empty() from @BeforeEach
        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\"," +
                  "\"params\":{\"name\":\"open_in_terminal\"," +
                  "\"arguments\":{\"id\":\"id-1\"}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].text",
                  equalTo("No terminal adapter available on this machine."));
    }

    @Test
    @Order(10)
    void getServerInfoReturnsExpectedFields() {
        given().contentType("application/json")
            .body("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\"," +
                  "\"params\":{\"name\":\"get_server_info\",\"arguments\":{}}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.content[0].text", containsString("Server URL:"))
            .body("result.content[0].text", containsString("Agent mode:"))
            .body("result.content[0].text", containsString("Terminal adapter: none"));
    }
}
