package dev.claudony.server;

import dev.claudony.Await;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TmuxServiceTest {

    @Inject TmuxService tmux;

    private static final String TEST_SESSION = "test-claudony-unit";

    @AfterEach
    void cleanup() throws Exception {
        if (tmux.sessionExists(TEST_SESSION)) tmux.killSession(TEST_SESSION);
    }

    @Test
    void tmuxVersionReturnsNonEmpty() throws Exception {
        var version = tmux.tmuxVersion();
        assertFalse(version.isBlank());
        assertTrue(version.startsWith("tmux"), "Expected 'tmux X.Y', got: " + version);
    }

    @Test
    void sessionDoesNotExistBeforeCreation() throws Exception {
        assertFalse(tmux.sessionExists(TEST_SESSION));
    }

    @Test
    void createAndKillSession() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo hello");
        assertTrue(tmux.sessionExists(TEST_SESSION));
        tmux.killSession(TEST_SESSION);
        assertFalse(tmux.sessionExists(TEST_SESSION));
    }

    @Test
    void listSessionNamesIncludesCreatedSession() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo hello");
        var names = tmux.listSessionNames();
        assertTrue(names.contains(TEST_SESSION),
                "Expected session list to contain: " + TEST_SESSION + ", got: " + names);
    }

    @Test
    void capturePaneReturnsOutput() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo claudony-marker");
        Await.until(() -> {
            try { return tmux.capturePane(TEST_SESSION, 20).contains("claudony-marker"); }
            catch (Exception e) { return false; }
        }, "'claudony-marker' to appear in pane output");
    }

    @Test
    void sendKeysLiteralModeDoesNotInterpretTmuxKeyNames() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "bash");
        // Wait for bash prompt before sending keys
        Await.until(() -> {
            try { return !tmux.capturePane(TEST_SESSION, 5).isBlank(); }
            catch (Exception e) { return false; }
        }, "bash prompt to appear");
        tmux.sendKeys(TEST_SESSION, "Escape");
        Await.until(() -> {
            try { return tmux.capturePane(TEST_SESSION, 20).contains("Escape"); }
            catch (Exception e) { return false; }
        }, "literal 'Escape' to appear in pane output (missing -l flag would fire key instead)");
    }
}
