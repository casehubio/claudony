package dev.claudony.server;

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
import dev.claudony.Await;
import dev.claudony.server.model.SessionStatus;
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

    private static final String TEST_SESSION = "test-claudony-ws";

    @BeforeEach
    void setup() throws Exception {
        // Use bash so the session stays alive; we send commands after pipe-pane connects
        tmux.createSession(TEST_SESSION, System.getProperty("user.home"), "bash");
        var now = Instant.now();
        registry.register(new dev.claudony.server.model.Session(
            "ws-test-id", TEST_SESSION, System.getProperty("user.home"),
            "bash", SessionStatus.IDLE, now, now, java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty()));
        Await.until(() -> {
            try { return !tmux.capturePane(TEST_SESSION, 5).isBlank(); }
            catch (Exception e) { return false; }
        }, "bash prompt to be ready in test session");
    }

    @AfterEach
    void cleanup() throws Exception {
        registry.remove("ws-test-id");
        if (tmux.sessionExists(TEST_SESSION)) tmux.killSession(TEST_SESSION);
    }

    /**
     * Drains the initial history burst after connecting.
     * The first message(s) are the history replay — wait until the queue
     * goes quiet for one poll interval, signalling the burst is done.
     */
    private static void awaitHistoryBurst(LinkedBlockingQueue<String> msgs) throws InterruptedException {
        var first = msgs.poll(2, TimeUnit.SECONDS);
        if (first == null) throw new AssertionError("No history message received within 2s");
        while (msgs.poll(20, TimeUnit.MILLISECONDS) != null) {}
    }

    /**
     * Happy-path: history must position cursor at the pane's actual cursor position.
     *
     * After sending history, xterm.js cursor must be at the same row/col as the
     * tmux pane cursor — not at the last text line of the history. Without this,
     * any content below the prompt (e.g. Claude Code's "? for shortcuts") causes
     * xterm.js cursor to land several rows below ❯, so typed input appears there.
     *
     * Implementation: history ends with ESC[row;colH (1-indexed) that matches the
     * pane cursor. We verify by reading the pane cursor from tmux and checking the
     * escape sequence is in the history message.
     */
    @Test
    void historyCursorPositionMatchesPaneCursorPosition() throws Exception {
        var container = ContainerProvider.getWebSocketContainer();
        var cec = ClientEndpointConfig.Builder.create().build();

        // Run a command so the pane has some history content and a known cursor state
        var live = new LinkedBlockingQueue<String>();
        var s1 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, live::offer);
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id/0/0"));
        awaitHistoryBurst(live);
        s1.getBasicRemote().sendText("echo CURSOR-SETUP\n");
        long d = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < d) {
            var c = live.poll(200, TimeUnit.MILLISECONDS); if (c != null && c.contains("CURSOR-SETUP")) break;
        }
        s1.close();

        // Read pane cursor position from tmux (0-indexed)
        var curProc = new ProcessBuilder("tmux", "display-message", "-p", "-t", TEST_SESSION,
            "#{cursor_y} #{cursor_x} #{pane_height}").start();
        String cursorInfo = new String(curProc.getInputStream().readAllBytes()).trim();
        curProc.waitFor();
        String[] parts = cursorInfo.split(" ");
        int cursorY = Integer.parseInt(parts[0]);   // 0-indexed pane row
        int cursorX = Integer.parseInt(parts[1]);   // 0-indexed pane col
        int paneHeight = Integer.parseInt(parts[2]);

        // Connect and collect the history message (first message from server)
        var msgs = new LinkedBlockingQueue<String>();
        var s2 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, msgs::offer);
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id/0/0"));
        String firstMsg = msgs.poll(2000, TimeUnit.MILLISECONDS);
        s2.close();

        assertNotNull(firstMsg, "History message must be received on connect");

        // The history must end with ESC[row;colH positioning the cursor exactly at
        // the pane cursor. For the capture (-S -100) with captureSize lines and
        // paneHeight rows, pane row cursorY corresponds to capture line
        // (captureSize - paneHeight + cursorY). If firstContent=0 (no non-blank
        // scrollback), xtermsRow = captureSize - paneHeight + cursorY + 1.
        // For a clean bash session without scrollback, captureSize == paneHeight,
        // so xtermsRow = cursorY + 1 (1-indexed).
        int expectedRow = cursorY + 1;   // 1-indexed for ESC[row;colH
        int expectedCol = cursorX + 1;   // 1-indexed
        String expectedCursorSeq = "\u001B[" + expectedRow + ";" + expectedCol + "H";

        assertTrue(firstMsg.contains(expectedCursorSeq),
            "History must contain ESC[" + expectedRow + ";" + expectedCol + "H " +
            "to position xterm.js cursor at pane cursor (row=" + cursorY + ", col=" + cursorX + "). " +
            "Without it, cursor ends at last history line, causing typed input to appear at wrong position. " +
            "Last 80 chars of history: [" +
            firstMsg.substring(Math.max(0, firstMsg.length() - 80))
                    .replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "ESC[...]") + "]");
    }

    /**
     * Happy-path: typed input must appear on the prompt line, not on a separate line.
     *
     * After connecting to a session, user types a command. The typed characters must
     * echo on the SAME line as the shell prompt ("$ "), not on a blank line below it.
     * Bug: cursor is left at the last history line rather than the prompt; the initial
     * pipe-pane \r\n flush then moves it one more line down; typed chars land there.
     *
     * Observable: type "echo INPUTTEST", the echoed command should appear immediately
     * following the prompt on the same logical row. If it appears after extra newlines,
     * the cursor was off.
     */
    @Test
    void typedInputEchoesOnPromptLineNotBelowIt() throws Exception {
        var container = ContainerProvider.getWebSocketContainer();
        var cec = ClientEndpointConfig.Builder.create().build();

        // Establish history so we're testing reconnect-style state
        var live1 = new LinkedBlockingQueue<String>();
        var s1 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, live1::offer);
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id/0/0"));
        awaitHistoryBurst(live1);
        s1.getBasicRemote().sendText("echo INPUTTEST-SETUP\n");
        long d = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < d) {
            var c = live1.poll(200, TimeUnit.MILLISECONDS);
            if (c != null && c.contains("INPUTTEST-SETUP")) break;
        }
        s1.close();

        // Connect fresh, drain history
        var msgs = new LinkedBlockingQueue<String>();
        var s2 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, msgs::offer);
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id/0/0"));

        // Wait for and discard history burst (give extra time for pipe-pane \r\n flush too)
        awaitHistoryBurst(msgs);

        // Now type a command — characters should echo at the prompt position
        s2.getBasicRemote().sendText("echo INPUTTEST-RESULT\n");

        // Collect pipe-pane output until we see the result
        var pipeOutput = new StringBuilder();
        d = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < d) {
            var chunk = msgs.poll(200, TimeUnit.MILLISECONDS);
            if (chunk != null) {
                pipeOutput.append(chunk);
                if (pipeOutput.toString().contains("INPUTTEST-RESULT")) break;
            }
        }
        s2.close();

        String output = pipeOutput.toString();
        assertTrue(output.contains("INPUTTEST-RESULT"),
            "Command output must appear in pipe-pane. Got: " + output.replace('\n','|').replace('\r','|').substring(0, Math.min(200, output.length())));

        // Find where "echo INPUTTEST-RESULT" appears in the raw pipe output.
        // Strip ANSI sequences to check the plain text position.
        String plain = output.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
        int echoIdx = plain.indexOf("echo INPUTTEST-RESULT");
        assertTrue(echoIdx >= 0, "echo INPUTTEST-RESULT must appear in pipe output");

        // The character BEFORE "echo INPUTTEST-RESULT" must NOT be a bare \n or \r\n.
        // A bare newline before "echo" means the typed command appeared on a new line
        // (cursor was below the prompt). Correct behavior: it follows the prompt on
        // the same line, so the char before is a space (part of "$ ").
        if (echoIdx > 0) {
            String beforeEcho = plain.substring(Math.max(0, echoIdx - 3), echoIdx);
            assertFalse(beforeEcho.endsWith("\n"),
                "Typed command 'echo INPUTTEST-RESULT' must not be preceded by bare newline. " +
                "Preceding 3 chars: [" + beforeEcho.replace('\n','|').replace('\r','|') + "]. " +
                "A leading \\n means the cursor was below the prompt (cursor positioning bug). " +
                "Full plain output: [" + plain.substring(0, Math.min(300, plain.length())).replace('\n','|').replace('\r','|') + "]");
        }
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
        }, cec, URI.create(wsBaseUri + "ws-test-id/0/0"));

        // Wait for pipe-pane to connect (drain history burst), then send a command to generate output
        awaitHistoryBurst(received);
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
        }, cec, URI.create(wsBaseUri + "unknown-id/0/0"));

        assertTrue(closed.await(2, TimeUnit.SECONDS),
            "Expected WebSocket to close for unknown session");
    }

    /**
     * Regression test: blank lines within pane content must be preserved in history.
     *
     * TUI apps (Claude Code, vim) use absolute cursor positioning (ESC[row;col H).
     * If blank lines are removed from the history, all subsequent rows shift UP in
     * xterm.js. When the TUI app then sends ESC[13;1H to update the prompt at
     * pane row 13, xterm.js moves to row 13 — but the history put that content
     * at row 12 (shifted due to the removed blank at row 12). The TUI app's output
     * lands ONE ROW BELOW the history's stale copy, producing a visible duplicate.
     *
     * Fix: preserve blank lines within the content range (between first and last
     * non-blank lines). Only strip leading blanks (scrollback) and trailing blanks
     * (pane padding). This ensures xterm.js row N == pane row N for visible content.
     */
    @Test
    void internalBlankLinesPreservedInHistoryToPreservePaneRowPositions() throws Exception {
        var container = ContainerProvider.getWebSocketContainer();
        var cec = ClientEndpointConfig.Builder.create().build();

        // Connect with real dimensions so resize always fires and pane is a known 80x24.
        // 0/0 skips resize, leaving the pane size undefined — which can cause the printf
        // output to scroll differently and put the blank line in an ambiguous position.
        var live = new LinkedBlockingQueue<String>();
        var session1 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, live::offer);
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id/80/24"));
        awaitHistoryBurst(live);

        // printf interprets \n in format strings: outputs MARK-A, blank line, MARK-B.
        session1.getBasicRemote().sendText("printf 'MARK-A\\n\\nMARK-B\\n'\n");

        // Wait for MARK-B in live output (confirms both lines appeared in pane).
        var liveText = new StringBuilder();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var chunk = live.poll(200, TimeUnit.MILLISECONDS);
            if (chunk != null) { liveText.append(chunk); if (liveText.toString().contains("MARK-B")) break; }
        }
        assertTrue(liveText.toString().contains("MARK-B"), "MARK-B must appear in live output. Got: " + liveText);

        // Wait until capture-pane itself confirms both markers are in the pane.
        // This eliminates timing races between the live FIFO delivery and the
        // pane grid state — they are independent code paths in tmux.
        Await.until(() -> {
            try {
                var pane = tmux.capturePane(TEST_SESSION, 50);
                return pane.contains("MARK-A") && pane.contains("MARK-B");
            } catch (Exception e) { return false; }
        }, "MARK-A and MARK-B to appear in captured pane");

        session1.close();
        Thread.sleep(200); // brief pause for pipe-pane teardown

        // Second connection: capture history and verify blank line is preserved.
        var history = new LinkedBlockingQueue<String>();
        var session2 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, history::offer);
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id/80/24"));

        // The regex: MARK-A on its own line → blank/ANSI line → MARK-B on its own line.
        // The echoed command "printf 'MARK-A\n\nMARK-B\n'" has both markers on ONE line
        // with LITERAL \n chars — this regex requires actual newlines, so it only matches
        // the actual printf output, not the echo. Break when this pattern appears.
        var blankLinePattern = java.util.regex.Pattern.compile("MARK-A[^\n]*\n[^\n]*\n[^\n]*MARK-B");

        var historyText = new StringBuilder();
        deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var chunk = history.poll(100, TimeUnit.MILLISECONDS);
            if (chunk != null) {
                historyText.append(chunk);
                if (blankLinePattern.matcher(historyText.toString()).find()) break;
            }
        }
        session2.close();

        String hist = historyText.toString();
        assertTrue(hist.contains("MARK-A"), "History must contain MARK-A");
        assertTrue(hist.contains("MARK-B"), "History must contain MARK-B");

        // Blank line must be preserved between MARK-A and MARK-B output.
        // Without it TUI app absolute cursor (ESC[row;colH) lands one row below the stale
        // history copy, producing a visible duplicate prompt.
        assertTrue(blankLinePattern.matcher(hist).find(),
            "A blank line must be preserved between MARK-A and MARK-B output. " +
            "Regression: removing it shifts rows in xterm.js causing duplicate TUI prompts. " +
            "History (first 500 chars): [" +
            hist.substring(0, Math.min(500, hist.length())).replace('\n', '|').replace('\r', '|') + "]");
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
        }, cec, URI.create(wsBaseUri + "ws-test-id/0/0"));
        awaitHistoryBurst(received);
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

        // Second connection: collect everything sent on connect (the history replay)
        var history = new LinkedBlockingQueue<String>();
        var session2 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, history::offer);
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id/0/0"));

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

    /**
     * Regression test: tmux pane resize must happen BEFORE capture-pane in @OnOpen.
     *
     * A bash script registers a SIGWINCH trap that prints a unique marker to the pty.
     * When the WebSocket connects with new dimensions, @OnOpen resizes the pane
     * (delivering SIGWINCH) before calling capture-pane.
     *
     * With the fix:
     *   resize → SIGWINCH → marker written to pty → 150ms wait → capture-pane sees marker
     *   → history sent to client → client receives marker.
     *
     * Without the fix (old bug):
     *   capture-pane first (no marker yet) → history sent (no marker) →
     *   resize → SIGWINCH → marker written to pty BEFORE pipe-pane starts →
     *   pipe-pane never captures it → client never receives marker.
     *
     * This asymmetry makes the test conclusive: if marker is absent the fix was reverted.
     */
    /**
     * Regression test: tmux window resize must happen BEFORE capture-pane in @OnOpen.
     *
     * Technique: content that wraps at 60 cols but fits on one line at 80 cols.
     *   - 65 'X' characters: "echo " (5) + 65 X's = 70 chars → wraps at 60, fits at 80.
     *   - If capture-pane runs at 60 cols (bug): the X's appear split across two lines
     *     in the history; `"X".repeat(65)` is NOT present as a single run.
     *   - If capture-pane runs at 80 cols (fix, after resize-window): the X's appear on
     *     one line; `"X".repeat(65)` IS present as a single unbroken run.
     *
     * resize-window is used (not resize-pane) because resize-pane is a no-op for
     * detached tmux sessions with no attached clients.
     */
    @Test
    void resizeHappensBeforeHistoryCaptureOnWebSocketOpen() throws Exception {
        // 65 X's: "echo " prefix (5 chars) + 65 X's = 70 chars.
        // Wraps at 60 cols (into lines of 60 and 10), fits on one line at 80 cols.
        final String XRUN = "X".repeat(65);
        final String extraSession = "test-claudony-resize-order";
        final String extraId     = "resize-order-test-id";

        // Kill any stale session from a previous failed run
        if (tmux.sessionExists(extraSession)) tmux.killSession(extraSession);
        registry.remove(extraId);

        tmux.createSession(extraSession, System.getProperty("user.home"), "bash");
        var now = Instant.now();
        registry.register(new dev.claudony.server.model.Session(
            extraId, extraSession, System.getProperty("user.home"),
            "bash", SessionStatus.IDLE, now, now, java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty()));

        try {
            Await.until(() -> {
                try { return !tmux.capturePane(extraSession, 5).isBlank(); }
                catch (Exception e) { return false; }
            }, "shell to start");

            // Set the window to 60 cols — narrower than the 80 we'll connect with.
            // resize-window works for detached sessions; resize-pane does not.
            new ProcessBuilder("tmux", "resize-window", "-t", extraSession, "-x", "60", "-y", "24")
                .redirectErrorStream(true).start().waitFor();
            // waitFor() ensures the resize command completed; no extra sleep needed.

            // Write the 65-X line to the pane. At 60 cols it wraps; after resize-window
            // to 80 cols, tmux reflows it to a single line.
            new ProcessBuilder("tmux", "send-keys", "-t", extraSession, "echo " + XRUN, "Enter")
                .redirectErrorStream(true).start().waitFor();
            Await.until(() -> {
                try { return tmux.capturePane(extraSession, 20).contains("X"); }
                catch (Exception e) { return false; }
            }, "output to settle in pane");

            // Sanity: at 60 cols the X's must be split — confirms the pane is really narrow.
            var preCap = new ProcessBuilder("tmux", "capture-pane", "-t", extraSession, "-p")
                .redirectErrorStream(false).start();
            var preCapOut = new String(preCap.getInputStream().readAllBytes());
            preCap.waitFor();
            assertFalse(preCapOut.contains(XRUN),
                "Pre-connect sanity: 65 X's must be wrapped (split) at 60 cols. " +
                "If unsplit, the pane isn't at 60 cols and the test can't distinguish fix from bug.");

            // Connect at width=80 — @OnOpen does: resize-window 60→80 → 150ms wait →
            // capture-pane. tmux reflows pane content on resize, so after resize the
            // X's appear on a single line in the capture.
            var messages = new LinkedBlockingQueue<String>();
            var container = ContainerProvider.getWebSocketContainer();
            var ws = container.connectToServer(new Endpoint() {
                @Override public void onOpen(Session s, EndpointConfig c) {
                    s.addMessageHandler(String.class, messages::offer);
                }
            }, ClientEndpointConfig.Builder.create().build(),
               URI.create(wsBaseUri + extraId + "/80/24"));

            // History is sent as ONE sendTextAndAwait() call before pipe-pane starts.
            // The first message received is the complete history burst.
            //
            // With the fix (resize-window before capture):
            //   resize 60→80 → pane reflows → 65 X's unwrap to single line → 150ms wait →
            //   capture-pane (sees unwrapped X's) → first message contains XRUN ✓
            //
            // Without the fix (capture before resize):
            //   capture at 60 cols → X's still wrapped → first message has split X's (no XRUN) →
            //   resize → pane reflows → subsequent pipe-pane messages may have XRUN ✗
            String firstMessage = messages.poll(2000, TimeUnit.MILLISECONDS);
            ws.close();

            assertNotNull(firstMessage,
                "Should receive a history message on WebSocket connect.");
            String stripped = firstMessage.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "")
                                          .replace('\r', ' ');
            assertTrue(stripped.contains(XRUN),
                "History must contain the 65-X run as a single line (captured at 80 cols). " +
                "Regression (capture before resize): X's remain wrapped at 60 cols in history, " +
                "XRUN is absent. History (stripped): [" +
                stripped.substring(0, Math.min(500, stripped.length())) + "]");
        } finally {
            registry.remove(extraId);
            if (tmux.sessionExists(extraSession)) tmux.killSession(extraSession);
        }
    }

    @Test
    void concurrentConnectionsToSameSessionDoNotCrash() throws Exception {
        var container = ContainerProvider.getWebSocketContainer();
        var cec = ClientEndpointConfig.Builder.create().build();
        var opened1 = new CountDownLatch(1);
        var opened2 = new CountDownLatch(1);
        var msgs1 = new LinkedBlockingQueue<String>();
        var received2 = new LinkedBlockingQueue<String>();

        // First connection
        var session1 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, msgs1::offer);
                opened1.countDown();
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id/0/0"));
        assertTrue(opened1.await(3, TimeUnit.SECONDS), "First connection should open");
        awaitHistoryBurst(msgs1);

        // Second connection: its pipe-pane replaces first connection's pipe
        var session2 = container.connectToServer(new Endpoint() {
            @Override public void onOpen(Session s, EndpointConfig c) {
                s.addMessageHandler(String.class, received2::offer);
                opened2.countDown();
            }
        }, cec, URI.create(wsBaseUri + "ws-test-id/0/0"));
        assertTrue(opened2.await(3, TimeUnit.SECONDS), "Second connection should open");
        awaitHistoryBurst(received2);

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
