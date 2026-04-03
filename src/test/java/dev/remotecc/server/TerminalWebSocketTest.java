package dev.remotecc.server;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
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
class TerminalWebSocketTest {

    @TestHTTPResource("/ws/")
    URI wsBaseUri;

    @Inject SessionRegistry registry;
    @Inject TmuxService tmux;

    private static final String TEST_SESSION = "test-remotecc-ws";

    @BeforeEach
    void setup() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo ws-test-marker");
        var now = Instant.now();
        registry.register(new dev.remotecc.server.model.Session(
            "ws-test-id", TEST_SESSION, System.getProperty("user.home"),
            "echo ws-test-marker", SessionStatus.IDLE, now, now));
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

        var message = received.poll(3, TimeUnit.SECONDS);
        assertNotNull(message, "Expected terminal output within 3s");
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
}
