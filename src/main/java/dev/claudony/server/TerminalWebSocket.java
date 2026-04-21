package dev.claudony.server;

import dev.claudony.server.model.SessionExpiredEvent;
import io.quarkus.websockets.next.*;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket terminal streaming without requiring a PTY.
 *
 * Instead of `tmux attach-session` (which requires a real terminal/PTY),
 * we use:
 *   OUTPUT: tmux pipe-pane → cat → FIFO → Java reads → WebSocket
 *   INPUT:  WebSocket → tmux send-keys -l (literal, no key name lookup)
 *
 * This works in a headless JVM/native environment where no TTY is available.
 */
@WebSocket(path = "/ws/{id}/{cols}/{rows}")
public class TerminalWebSocket {

    private static final Logger LOG = Logger.getLogger(TerminalWebSocket.class);

    @Inject SessionRegistry registry;

    // connection.id() → tmux session name (needed in @OnTextMessage)
    private final ConcurrentHashMap<String, String> sessionNames = new ConcurrentHashMap<>();

    // connection.id() → FIFO path (for cleanup)
    private final ConcurrentHashMap<String, String> fifoPaths = new ConcurrentHashMap<>();

    // connection.id() → session id (needed to touch registry on message)
    private final ConcurrentHashMap<String, String> sessionIds = new ConcurrentHashMap<>();

    // connection.id() → WebSocketConnection (needed to send expiry message)
    private final ConcurrentHashMap<String, WebSocketConnection> connections = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        var sessionId = connection.pathParam("id");
        var session = registry.find(sessionId);

        if (session.isEmpty()) {
            LOG.warnf("WebSocket open for unknown session id=%s — closing", sessionId);
            try { connection.closeAndAwait(); } catch (Exception ignored) {}
            return;
        }

        var tmuxName = session.get().name();
        var fifoPath = "/tmp/claudony-" + connection.id() + ".pipe";
        int cols = parsePathInt(connection.pathParam("cols"));
        int rows = parsePathInt(connection.pathParam("rows"));
        LOG.debugf("WebSocket open for session '%s' (id=%s) at %dx%d", tmuxName, sessionId, cols, rows);

        sessionNames.put(connection.id(), tmuxName);
        sessionIds.put(connection.id(), sessionId);
        connections.put(connection.id(), connection);

        try {
            // Step 1: Resize the tmux window FIRST, before capturing history.
            // resize-window (not resize-pane) works for detached sessions with no
            // attached clients — resize-pane is silently constrained by client size.
            // This delivers SIGWINCH to any TUI app (Claude Code, vim, etc.), causing
            // it to redraw at the correct size. The 150ms wait lets the app redraw, so
            // capture-pane gets the fresh post-resize state instead of the stale pre-resize
            // state. Without this ordering, the stale snapshot arrives via history and the
            // post-resize redraw arrives via pipe-pane, producing a duplicate prompt.
            if (cols > 0 && rows > 0) {
                new ProcessBuilder("tmux", "resize-window", "-t", tmuxName,
                        "-x", String.valueOf(cols), "-y", String.valueOf(rows))
                        .redirectErrorStream(true).start().waitFor();
                Thread.sleep(150); // allow TUI app to process SIGWINCH and redraw
                LOG.debugf("Resized window '%s' to %dx%d before history capture", tmuxName, cols, rows);
            }

            // Step 2: Send history BEFORE pipe-pane starts to avoid race conditions.
            // Use -e to preserve ANSI color codes. Lines are still padded to pane
            // width — detect blank lines by stripping ANSI codes first, then include
            // the original colored line with trailing whitespace removed.
            var cap = new ProcessBuilder("tmux", "capture-pane", "-t", tmuxName,
                    "-e", "-p", "-S", "-100")
                    .redirectErrorStream(false).start();
            var raw = new String(cap.getInputStream().readAllBytes());
            cap.waitFor();
            if (!raw.isBlank()) {
                // Find first and last non-blank lines to determine the content range.
                // CRITICAL: blank lines WITHIN the range must be preserved so that
                // xterm.js row N == pane row N for all visible content. If internal
                // blank lines are removed, content shifts up in xterm.js, causing TUI
                // apps (Claude Code, vim) that use absolute cursor positioning
                // (ESC[row;col H) to land on the wrong row — one row below the stale
                // history copy — producing a visible duplicate prompt.
                // We only strip leading blanks (old scrollback) and trailing blanks
                // (pane row padding).
                var lines = raw.split("\n", -1);
                int firstContent = -1, lastContent = -1;
                for (int i = 0; i < lines.length; i++) {
                    var plain = lines[i].replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "").stripTrailing();
                    if (!plain.isEmpty()) {
                        if (firstContent < 0) firstContent = i;
                        lastContent = i;
                    }
                }
                if (firstContent >= 0) {
                    var contentLines = new java.util.ArrayList<String>();
                    for (int i = firstContent; i <= lastContent; i++) {
                        var plain = lines[i].replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "").stripTrailing();
                        // Visually blank lines (only ANSI codes, no printable text) must be
                        // stored as truly empty strings so they produce \r\n\r\n in the join
                        // and xterm.js renders a blank row, preserving pane row alignment.
                        contentLines.add(plain.isEmpty() ? "" : lines[i].stripTrailing());
                    }

                    // Get pane cursor position so we can reposition xterm.js cursor
                    // to exactly where the pane cursor is after sending history.
                    // Without this, xterm.js cursor lands at the last history line
                    // (e.g. Claude Code's "? for shortcuts") — not at the ❯ prompt —
                    // causing typed input to appear at the wrong row.
                    String cursorSeq = "";
                    try {
                        var dimProc = new ProcessBuilder("tmux", "display-message", "-p",
                                "-t", tmuxName, "#{cursor_y} #{cursor_x} #{pane_height}")
                                .redirectErrorStream(false).start();
                        var dimStr = new String(dimProc.getInputStream().readAllBytes()).trim();
                        dimProc.waitFor();
                        var dimParts = dimStr.split(" ");
                        if (dimParts.length == 3) {
                            int paneCursorY  = Integer.parseInt(dimParts[0]); // 0-indexed
                            int paneCursorX  = Integer.parseInt(dimParts[1]); // 0-indexed
                            int paneH        = Integer.parseInt(dimParts[2]);
                            // Map pane cursor row → xterm.js row (1-indexed).
                            // capture-pane -S -100 output ends with \n so split() adds one
                            // extra empty element: effective capture size = lines.length - 1.
                            // Pane rows are the last paneH of those effective lines.
                            //   paneRowInCapture = (captureSize - paneH) + paneCursorY
                            //   xtermsRow        = paneRowInCapture - firstContent + 1
                            int captureSize = lines[lines.length - 1].isEmpty()
                                    ? lines.length - 1 : lines.length;
                            int paneRowInCapture = (captureSize - paneH) + paneCursorY;
                            int xtermsRow = paneRowInCapture - firstContent + 1; // 1-indexed
                            int xtermsCol = paneCursorX + 1;                     // 1-indexed
                            if (xtermsRow >= 1) {
                                cursorSeq = "\u001B[" + xtermsRow + ";" + xtermsCol + "H";
                            }
                        }
                    } catch (IOException | InterruptedException e) {
                        LOG.debugf("Could not read pane cursor for %s: %s", tmuxName, e.getMessage());
                    }

                    var history = String.join("\r\n", contentLines) + "\u001B[0m" + cursorSeq;
                    connection.sendTextAndAwait(history);
                }
            }

            // Step 3: Set up FIFO + pipe-pane for live output streaming.
            new ProcessBuilder("mkfifo", fifoPath)
                    .redirectErrorStream(true).start().waitFor();
            fifoPaths.put(connection.id(), fifoPath);

            // Virtual thread: reads from FIFO → sends to WebSocket.
            // Skips the initial \r\n (or \n) that pipe-pane flushes when the FIFO
            // first connects (BUGS-AND-ODDITIES #12). Without this skip, that newline
            // moves xterm.js cursor one row past the cursor position we just set,
            // causing typed input to appear one row below the prompt.
            Thread.ofVirtual().start(() -> {
                try (var in = new BufferedInputStream(new FileInputStream(fifoPath))) {
                    var buf = new byte[4096];
                    int n;
                    boolean firstRead = true;
                    while ((n = in.read(buf)) != -1) {
                        if (firstRead) {
                            firstRead = false;
                            // Skip if first read is exactly \n or \r\n — that's the
                            // pipe-pane initial flush artifact. Any other content is real.
                            if (n == 1 && buf[0] == '\n') continue;
                            if (n == 2 && buf[0] == '\r' && buf[1] == '\n') continue;
                        }
                        connection.sendTextAndAwait(new String(buf, 0, n));
                    }
                } catch (IOException e) {
                    LOG.debugf("FIFO stream ended for session %s: %s", sessionId, e.getMessage());
                }
            });

            // Connect pane output to FIFO via pipe-pane (cat bridges tmux → FIFO → Java)
            var p = new ProcessBuilder("tmux", "pipe-pane", "-t", tmuxName,
                    "cat > " + fifoPath)
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            p.waitFor();
            registry.touch(sessionId);

        } catch (IOException | InterruptedException e) {
            LOG.errorf("Failed to set up pipe for session '%s': %s", tmuxName, e.getMessage());
            cleanup(connection);
            try { connection.closeAndAwait(); } catch (Exception ignored) {}
        }
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        var tmuxName = sessionNames.get(connection.id());
        if (tmuxName == null) return;
        var sid = sessionIds.get(connection.id());
        if (sid != null) registry.touch(sid);
        try {
            // -l sends text literally (no tmux key name lookup)
            // This handles plain text, control chars (\x03, \x04), and escape sequences
            var p = new ProcessBuilder("tmux", "send-keys", "-t", tmuxName, "-l", message)
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            LOG.debugf("Failed to send input to session %s: %s", tmuxName, e.getMessage());
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        cleanup(connection);
        LOG.debugf("WebSocket closed for connection %s", connection.id());
    }

    @OnError
    public void onError(Throwable error, WebSocketConnection connection) {
        LOG.warnf("WebSocket error for connection %s: %s", connection.id(), error.getMessage());
        cleanup(connection);
    }

    private static int parsePathInt(String value) {
        try { return value != null ? Integer.parseInt(value) : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    void onSessionExpired(@Observes SessionExpiredEvent event) {
        var expiredName = event.session().name();
        sessionNames.entrySet().stream()
                .filter(e -> e.getValue().equals(expiredName))
                .map(e -> connections.get(e.getKey()))
                .filter(java.util.Objects::nonNull)
                .forEach(conn -> Thread.ofVirtual().start(() -> {
                    try { conn.sendTextAndAwait("{\"type\":\"session-expired\"}"); }
                    catch (Exception e) {
                        LOG.debugf("Could not send session-expired to connection: %s", e.getMessage());
                    }
                }));
    }

    private void cleanup(WebSocketConnection connection) {
        // Stop pipe-pane (calling pipe-pane with no command stops it)
        var tmuxName = sessionNames.remove(connection.id());
        if (tmuxName != null) {
            try {
                var p = new ProcessBuilder("tmux", "pipe-pane", "-t", tmuxName)
                        .redirectErrorStream(true).start();
                p.getInputStream().transferTo(OutputStream.nullOutputStream());
            } catch (Exception ignored) {}
        }
        // Delete the FIFO
        var fifoPath = fifoPaths.remove(connection.id());
        if (fifoPath != null) {
            try { Files.deleteIfExists(Path.of(fifoPath)); } catch (Exception ignored) {}
        }
        sessionIds.remove(connection.id());
        connections.remove(connection.id());
    }
}
