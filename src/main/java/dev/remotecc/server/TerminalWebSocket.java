package dev.remotecc.server;

import io.quarkus.websockets.next.*;
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
        var fifoPath = "/tmp/remotecc-" + connection.id() + ".pipe";
        int cols = parsePathInt(connection.pathParam("cols"));
        int rows = parsePathInt(connection.pathParam("rows"));
        LOG.debugf("WebSocket open for session '%s' (id=%s) at %dx%d", tmuxName, sessionId, cols, rows);

        sessionNames.put(connection.id(), tmuxName);

        try {
            // Step 1: Send history BEFORE pipe-pane starts to avoid race conditions.
            // Use -e to preserve ANSI color codes. Lines are still padded to pane
            // width — detect blank lines by stripping ANSI codes first, then include
            // the original colored line with trailing whitespace removed.
            var cap = new ProcessBuilder("tmux", "capture-pane", "-t", tmuxName,
                    "-e", "-p", "-S", "-100")
                    .redirectErrorStream(false).start();
            var raw = new String(cap.getInputStream().readAllBytes());
            cap.waitFor();
            if (!raw.isBlank()) {
                // Collect non-blank lines, join with \r\n between them (not after last).
                // Cursor stays at end of last line (the current prompt) so pipe-pane
                // output continues from there without adding a blank line.
                var contentLines = new java.util.ArrayList<String>();
                for (var line : raw.split("\n", -1)) {
                    var plain = line.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "").stripTrailing();
                    if (!plain.isEmpty()) {
                        contentLines.add(line.stripTrailing());
                    }
                }
                if (!contentLines.isEmpty()) {
                    // Restore one trailing space on the last line (the current prompt)
                    // so cursor is positioned correctly after "$ " for user input.
                    var last = contentLines.size() - 1;
                    contentLines.set(last, contentLines.get(last) + " ");
                    var history = String.join("\r\n", contentLines) + "\u001B[0m";
                    connection.sendTextAndAwait(history);
                }
            }

            // Step 2: Resize tmux pane to the browser dimensions and deliver SIGWINCH.
            // This causes any running TUI app (Claude Code, vim, etc.) to redraw at the
            // correct size BEFORE pipe-pane starts streaming — so the user never sees
            // the garbled history-replay state.
            if (cols > 0 && rows > 0) {
                new ProcessBuilder("tmux", "resize-pane", "-t", tmuxName,
                        "-x", String.valueOf(cols), "-y", String.valueOf(rows))
                        .redirectErrorStream(true).start().waitFor();
                LOG.debugf("Resized pane '%s' to %dx%d to trigger TUI redraw", tmuxName, cols, rows);
            }

            // Step 3: Set up FIFO + pipe-pane for live output streaming.
            new ProcessBuilder("mkfifo", fifoPath)
                    .redirectErrorStream(true).start().waitFor();
            fifoPaths.put(connection.id(), fifoPath);

            // Virtual thread: reads from FIFO → sends to WebSocket
            Thread.ofVirtual().start(() -> {
                try (var in = new BufferedInputStream(new FileInputStream(fifoPath))) {
                    var buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
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

        } catch (Exception e) {
            LOG.errorf("Failed to set up pipe for session '%s': %s", tmuxName, e.getMessage());
            cleanup(connection);
            try { connection.closeAndAwait(); } catch (Exception ignored) {}
        }
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        var tmuxName = sessionNames.get(connection.id());
        if (tmuxName == null) return;
        try {
            // -l sends text literally (no tmux key name lookup)
            // This handles plain text, control chars (\x03, \x04), and escape sequences
            var p = new ProcessBuilder("tmux", "send-keys", "-t", tmuxName, "-l", message)
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            p.waitFor();
        } catch (Exception e) {
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
    }
}
