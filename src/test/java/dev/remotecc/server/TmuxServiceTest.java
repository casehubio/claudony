package dev.remotecc.server;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;
import jakarta.inject.Inject;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TmuxServiceTest {

    @Inject
    TmuxService tmux;

    private static final String TEST_SESSION = "test-remotecc-unit";

    @AfterEach
    void cleanup() throws Exception {
        if (tmux.sessionExists(TEST_SESSION)) {
            tmux.killSession(TEST_SESSION);
        }
    }

    @Test
    @Order(1)
    void tmuxVersionReturnsNonEmpty() throws Exception {
        var version = tmux.tmuxVersion();
        assertFalse(version.isBlank());
        assertTrue(version.startsWith("tmux"), "Expected 'tmux X.Y', got: " + version);
    }

    @Test
    @Order(2)
    void sessionDoesNotExistBeforeCreation() throws Exception {
        assertFalse(tmux.sessionExists(TEST_SESSION));
    }

    @Test
    @Order(3)
    void createAndKillSession() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo hello");
        assertTrue(tmux.sessionExists(TEST_SESSION));
        tmux.killSession(TEST_SESSION);
        assertFalse(tmux.sessionExists(TEST_SESSION));
    }

    @Test
    @Order(4)
    void listSessionNamesIncludesCreatedSession() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo hello");
        var names = tmux.listSessionNames();
        assertTrue(names.contains(TEST_SESSION),
                "Expected session list to contain: " + TEST_SESSION + ", got: " + names);
    }

    @Test
    @Order(5)
    void capturePaneReturnsOutput() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo remotecc-marker");
        Thread.sleep(300);
        var output = tmux.capturePane(TEST_SESSION, 20);
        assertTrue(output.contains("remotecc-marker"),
                "Expected pane output to contain 'remotecc-marker', got: " + output);
    }
}
