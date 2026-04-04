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
@WebSocket(path = "/ws/{id}")
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
        LOG.debugf("WebSocket open for session '%s' (id=%s)", tmuxName, sessionId);

        sessionNames.put(connection.id(), tmuxName);

        try {
            // Create a named FIFO (pipe) for streaming pane output
            new ProcessBuilder("mkfifo", fifoPath)
                    .redirectErrorStream(true).start().waitFor();
            fifoPaths.put(connection.id(), fifoPath);

            // Virtual thread: reads from FIFO → sends to WebSocket
            // Opening the FIFO for reading blocks until a writer connects (pipe-pane below)
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

            // Connect pane output to the FIFO via pipe-pane.
            // tmux runs: cat > /path/fifo
            //   cat's stdin = pane output (tmux pipe-pane default direction)
            //   cat's stdout = FIFO (shell redirection) → Java reads above
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
