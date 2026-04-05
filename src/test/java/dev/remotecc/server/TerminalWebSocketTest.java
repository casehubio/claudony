package dev.remotecc.server;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.CloseReason;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.util.concurrent.*;
import dev.remotecc.server.model.SessionStatus;
import jakarta.websocket.Session;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class TerminalWebSocketTest {

    @TestHTTPResource("/ws/")
    URI wsBaseUri;

    @Inject SessionRegistry registry;
    @Inject TmuxService tmux;

    private static final String TEST_SESSION = "test-remotecc-ws";

    @BeforeEach
    void setup() throws Exception {
        // Use bash so the session stays alive; we send commands after pipe-pane connects
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "bash");
        var now = Instant.now();
        registry.register(new dev.remotecc.server.model.Session(
            "ws-test-id", TEST_SESSION, System.getProperty("user.home"),
            "bash", SessionStatus.IDLE, now, now));
        Thread.sleep(300);
    }

    @AfterEach
    void cleanup() throws Exception {
        registry.remove("ws-test-id");
        if (tmux.sessionExists(TEST_SESSION)) tmux.killSession(TEST_SESSION);
    }

    @Test
    void connectToSessionReceivesOutput() throws Exception {
        var received = new LinkedBlockingQueue<String>();
        var container = ContainerProvider.getWebSocketContainer();
        var cec = ClientEndpointConfig.Builder.create().build();
        var session = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, received::offer);
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id"));

        // Wait for pipe-pane to connect, then send a command to generate output
        Thread.sleep(500);
        session.getBasicRemote().sendText("echo ws-pipe-test\n");

        // Collect output chunks for up to 3s and check for our marker
        var sb = new StringBuilder();
        String chunk;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            chunk = received.poll(200, TimeUnit.MILLISECONDS);
            if (chunk != null) {
                sb.append(chunk);
                if (sb.toString().contains("ws-pipe-test")) break;
            }
        }
        assertTrue(sb.toString().contains("ws-pipe-test"),
            "Expected 'ws-pipe-test' in output, got: " + sb);
        session.close();
    }

    @Test
    void connectToUnknownSessionClosesConnection() throws Exception {
        var closed = new CountDownLatch(1);
        var container = ContainerProvider.getWebSocketContainer();
        var cec = ClientEndpointConfig.Builder.create().build();
        var session = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {}
            @Override public void onClose(Session s, CloseReason r) { closed.countDown(); }
        }, cec, URI.create(wsBaseUri + "unknown-id"));

        assertTrue(closed.await(2, TimeUnit.SECONDS),
            "Expected WebSocket to close for unknown session");
    }

    @Test
    void historyIsReplayedOnReconnect() throws Exception {
        var received = new LinkedBlockingQueue<String>();
        var container = ContainerProvider.getWebSocketContainer();
        var cec = ClientEndpointConfig.Builder.create().build();

        // First connection: send a command to generate visible output
        var session1 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, received::offer);
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id"));
        Thread.sleep(500);
        session1.getBasicRemote().sendText("echo history-replay-marker\n");

        // Wait for marker in live output
        var live = new StringBuilder();
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var chunk = received.poll(200, TimeUnit.MILLISECONDS);
            if (chunk != null) {
                live.append(chunk);
                if (live.toString().contains("history-replay-marker")) break;
            }
        }
        assertTrue(live.toString().contains("history-replay-marker"),
            "Marker must appear in live output before testing history replay. Got: " + live);
        session1.close();
        Thread.sleep(300);

        // Second connection: collect everything sent on connect (the history replay)
        var history = new LinkedBlockingQueue<String>();
        var session2 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, history::offer);
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id"));

        var historyText = new StringBuilder();
        deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            var chunk = history.poll(100, TimeUnit.MILLISECONDS);
            if (chunk != null) historyText.append(chunk);
        }
        session2.close();

        assertTrue(historyText.toString().contains("history-replay-marker"),
            "Reconnect history must contain 'history-replay-marker'. Got: " + historyText);
    }

    @Test
    void concurrentConnectionsToSameSessionDoNotCrash() throws Exception {
        var container = ContainerProvider.getWebSocketContainer();
        var cec = ClientEndpointConfig.Builder.create().build();
        var opened1 = new CountDownLatch(1);
        var opened2 = new CountDownLatch(1);
        var received2 = new LinkedBlockingQueue<String>();

        // First connection
        var session1 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, msg -> {});
                opened1.countDown();
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id"));
        assertTrue(opened1.await(3, TimeUnit.SECONDS), "First connection should open");
        Thread.sleep(300);

        // Second connection: its pipe-pane replaces first connection's pipe
        var session2 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, received2::offer);
                opened2.countDown();
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id"));
        assertTrue(opened2.await(3, TimeUnit.SECONDS), "Second connection should open");
        Thread.sleep(500);

        // Second connection holds the active pipe — it should receive output
        session2.getBasicRemote().sendText("echo concurrent-test-marker\n");
        var sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var chunk = received2.poll(200, TimeUnit.MILLISECONDS);
            if (chunk != null) {
                sb.append(chunk);
                if (sb.toString().contains("concurrent-test-marker")) break;
            }
        }
        assertTrue(sb.toString().contains("concurrent-test-marker"),
            "Second connection should receive output after both connected. Got: " + sb);

        // Both close cleanly
        session1.close();
        session2.close();
    }
}
