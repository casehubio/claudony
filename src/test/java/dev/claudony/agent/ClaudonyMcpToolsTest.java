package dev.claudony.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import dev.claudony.agent.terminal.TerminalAdapter;
import dev.claudony.agent.terminal.TerminalAdapterFactory;
import dev.claudony.server.model.CreateSessionRequest;
import dev.claudony.server.model.SessionResponse;
import dev.claudony.server.model.SessionStatus;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Tests for ClaudonyMcpTools tool logic via direct CDI injection.
 *
 * <p>No HTTP, no JSON-RPC, no session IDs — just the tool methods themselves.
 * Protocol compliance (Accept headers, Mcp-Session-Id, error codes) is tested
 * separately in McpProtocolTest.
 */
@QuarkusTest
class ClaudonyMcpToolsTest {

    @Inject
    ClaudonyMcpTools tools;

    @InjectMock
    @RestClient
    ServerClient serverClient;

    @InjectMock
    TerminalAdapterFactory terminalFactory;

    @BeforeEach
    void reset() {
        Mockito.reset(serverClient, terminalFactory);
        Mockito.when(terminalFactory.resolve()).thenReturn(Optional.empty());
    }

    @Test
    void listSessions_empty_returnsNoActiveMessage() {
        Mockito.when(serverClient.listSessions()).thenReturn(List.of());
        assertThat(tools.listSessions()).isEqualTo("No active sessions.");
    }

    @Test
    void listSessions_withSessions_formatsEachSession() {
        var now = Instant.now();
        Mockito.when(serverClient.listSessions()).thenReturn(List.of(
            new SessionResponse("id-1", "claudony-proj", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                "ws://localhost:7777/ws/id-1",
                "http://localhost:7777/app/session/id-1",
                null, null, null)));
        assertThat(tools.listSessions())
            .contains("claudony-proj")
            .contains("id-1")
            .contains("IDLE");
    }

    @Test
    void createSession_nullCommand_passesNullToServer() {
        var now = Instant.now();
        Mockito.when(serverClient.createSession(Mockito.any()))
            .thenReturn(new SessionResponse("id-2", "claudony-new", "/home", "claude",
                SessionStatus.IDLE, now, now,
                "ws://localhost:7777/ws/id-2",
                "http://localhost:7777/app/session/id-2",
                null, null, null));

        tools.createSession("new", "/home", null);

        Mockito.verify(serverClient).createSession(
            Mockito.argThat(r -> r.command() == null));
    }

    @Test
    void createSession_blankCommand_treatedAsNull() {
        var now = Instant.now();
        Mockito.when(serverClient.createSession(Mockito.any()))
            .thenReturn(new SessionResponse("id-3", "claudony-n", "/", "claude",
                SessionStatus.IDLE, now, now,
                "ws://localhost:7777/ws/id-3",
                "http://localhost:7777/app/session/id-3",
                null, null, null));

        tools.createSession("n", "/", "   ");

        Mockito.verify(serverClient).createSession(
            Mockito.argThat(r -> r.command() == null));
    }

    @Test
    void createSession_withCommand_passesCommandToServer() {
        var now = Instant.now();
        Mockito.when(serverClient.createSession(Mockito.any()))
            .thenReturn(new SessionResponse("id-4", "claudony-n", "/", "claude",
                SessionStatus.IDLE, now, now,
                "ws://localhost:7777/ws/id-4",
                "http://localhost:7777/app/session/id-4",
                null, null, null));

        tools.createSession("n", "/", "bash");

        Mockito.verify(serverClient).createSession(
            Mockito.argThat(r -> "bash".equals(r.command())));
    }

    @Test
    void deleteSession_delegatesToServer_andReturnsConfirmation() {
        assertThat(tools.deleteSession("id-1")).isEqualTo("Session deleted.");
        Mockito.verify(serverClient).deleteSession("id-1");
    }

    @Test
    void renameSession_returnsFormattedName() {
        var now = Instant.now();
        Mockito.when(serverClient.renameSession("id-1", "newname"))
            .thenReturn(new SessionResponse("id-1", "claudony-newname", "/tmp", "claude",
                SessionStatus.IDLE, now, now,
                "ws://localhost:7777/ws/id-1",
                "http://localhost:7777/app/session/id-1",
                null, null, null));

        assertThat(tools.renameSession("id-1", "newname")).contains("claudony-newname");
    }

    @Test
    void sendInput_passesTextLiterally() {
        tools.sendInput("id-1", "echo Escape marker\n");
        Mockito.verify(serverClient).sendInput(
            Mockito.eq("id-1"),
            Mockito.argThat(r -> "echo Escape marker\n".equals(r.text())));
    }

    @Test
    void getOutput_nullLines_defaultsTo50() {
        Mockito.when(serverClient.getOutput("id-1", 50)).thenReturn("output");
        tools.getOutput("id-1", null);
        Mockito.verify(serverClient).getOutput("id-1", 50);
    }

    @Test
    void getOutput_explicitLines_usesProvided() {
        Mockito.when(serverClient.getOutput("id-1", 20)).thenReturn("output");
        tools.getOutput("id-1", 20);
        Mockito.verify(serverClient).getOutput("id-1", 20);
    }

    @Test
    void openInTerminal_noAdapter_returnsHelpfulMessage() {
        assertThat(tools.openInTerminal("id-1"))
            .isEqualTo("No terminal adapter available on this machine.");
    }

    @Test
    void getServerInfo_containsExpectedFields() {
        assertThat(tools.getServerInfo())
            .contains("Server URL:")
            .contains("Agent mode:")
            .contains("Terminal adapter: none");
    }

    // ── Error handling ───────────────────────────────────────────────────────────

    @Test
    void listSessions_serverReturns500_returnsServerError() {
        Mockito.when(serverClient.listSessions())
            .thenThrow(new WebApplicationException(Response.status(500).build()));
        assertThat(tools.listSessions()).startsWith("Server error (HTTP 500)");
    }

    @Test
    void listSessions_serverUnreachable_returnsConnectError() {
        Mockito.when(serverClient.listSessions())
            .thenThrow(new ProcessingException("Connection refused"));
        assertThat(tools.listSessions()).startsWith("Unable to reach Claudony server");
    }

    @Test
    void createSession_serverReturns409_returnsConflictMessage() {
        Mockito.when(serverClient.createSession(Mockito.any()))
            .thenThrow(new WebApplicationException(Response.status(409).build()));
        assertThat(tools.createSession("dup", "/tmp", null)).contains("Conflict");
    }

    @Test
    void deleteSession_serverReturns404_returnsNotFoundMessage() {
        Mockito.doThrow(new WebApplicationException(Response.status(404).build()))
            .when(serverClient).deleteSession("bad-id");
        assertThat(tools.deleteSession("bad-id"))
            .contains("not found")
            .contains("list_sessions");
    }

    @Test
    void renameSession_serverReturns404_returnsNotFoundMessage() {
        Mockito.when(serverClient.renameSession(Mockito.eq("bad-id"), Mockito.any()))
            .thenThrow(new WebApplicationException(Response.status(404).build()));
        assertThat(tools.renameSession("bad-id", "new-name")).contains("not found");
    }

    @Test
    void sendInput_serverReturns404_returnsNotFoundMessage() {
        Mockito.doThrow(new WebApplicationException(Response.status(404).build()))
            .when(serverClient).sendInput(Mockito.eq("bad-id"), Mockito.any());
        assertThat(tools.sendInput("bad-id", "echo hi")).contains("not found");
    }

    @Test
    void getOutput_serverReturns500_returnsServerError() {
        Mockito.when(serverClient.getOutput(Mockito.eq("id-1"), Mockito.anyInt()))
            .thenThrow(new WebApplicationException(Response.status(500).build()));
        assertThat(tools.getOutput("id-1", null)).startsWith("Server error (HTTP 500)");
    }

    @Test
    void getServerInfo_doesNotContainNullLiteral() {
        // getServerInfo reads config — verify null-safety: output must not contain the string "null"
        assertThat(tools.getServerInfo()).doesNotContain("null");
    }
}
