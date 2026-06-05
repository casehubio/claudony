package io.casehub.claudony.server;

import io.casehub.claudony.Await;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TmuxServiceTest {

    @Inject
    TmuxService tmux;

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
    void displayMessageReturnsWindowActivity() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo hello");
        Thread.sleep(300);
        var activity = tmux.displayMessage(TEST_SESSION, "#{window_activity}");
        assertFalse(activity.isBlank(), "window_activity should return a non-empty timestamp");
        assertDoesNotThrow(() -> Long.parseLong(activity.trim()),
                "window_activity should be a numeric Unix timestamp, got: " + activity);
    }

    @Test
    void displayMessageReturnsPaneCurrentCommand() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "sleep 10");
        Thread.sleep(300);
        var command = tmux.displayMessage(TEST_SESSION, "#{pane_current_command}");
        assertFalse(command.isBlank());
    }

    @Test
    void createWorkerSession_sessionClosesWhenCommandExits() throws Exception {
        tmux.createWorkerSession(TEST_SESSION, System.getProperty("user.home"), "true");
        // "true" exits immediately; with direct command execution the session must close
        Await.until(() -> {
            try { return !tmux.sessionExists(TEST_SESSION); }
            catch (Exception e) { return false; }
        }, "session to close after direct command exits");
    }

    @Test
    void createWorkerSession_sessionExistsWhileCommandIsRunning() throws Exception {
        tmux.createWorkerSession(TEST_SESSION, System.getProperty("user.home"), "sleep 30");
        assertTrue(tmux.sessionExists(TEST_SESSION), "Session should be alive while command runs");
    }

    @Test
    void setAndGetSessionOption_roundTrips() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo hello");
        tmux.setSessionOption(TEST_SESSION, "@casehub_case_id", "test-uuid-value");
        var result = tmux.getSessionOption(TEST_SESSION, "@casehub_case_id");
        assertThat(result).isPresent().hasValue("test-uuid-value");
    }

    @Test
    void getSessionOption_returnsEmpty_whenKeyAbsent() throws Exception {
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "echo hello");
        var result = tmux.getSessionOption(TEST_SESSION, "@nonexistent_key");
        assertThat(result).isEmpty();
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
