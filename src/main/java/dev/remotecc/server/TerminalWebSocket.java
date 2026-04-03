package dev.remotecc.server;

import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.io.*;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket(path = "/ws/{id}")
public class TerminalWebSocket {

    private static final Logger LOG = Logger.getLogger(TerminalWebSocket.class);

    @Inject SessionRegistry registry;

    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        var sessionId = connection.pathParam("id");
        var session = registry.find(sessionId);

        if (session.isEmpty()) {
            LOG.warnf("WebSocket open for unknown session id=%s — closing", sessionId);
            try {
                connection.closeAndAwait();
            } catch (Exception e) {
                LOG.debugf("Exception while closing unknown session connection: %s", e.getMessage());
            }
            return;
        }

        var tmuxName = session.get().name();
        LOG.debugf("WebSocket open for session '%s' (id=%s)", tmuxName, sessionId);

        try {
            var process = new ProcessBuilder("tmux", "attach-session", "-t", tmuxName)
                    .redirectErrorStream(true)
                    .start();
            processes.put(connection.id(), process);

            Thread.ofVirtual().start(() -> {
                try (var in = new BufferedInputStream(process.getInputStream())) {
                    var buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        connection.sendTextAndAwait(new String(buf, 0, n));
                    }
                } catch (IOException e) {
                    LOG.debugf("Stdout pipe closed for session %s: %s", sessionId, e.getMessage());
                }
            });

        } catch (IOException e) {
            LOG.errorf("Failed to attach to tmux session '%s': %s", tmuxName, e.getMessage());
            try {
                connection.closeAndAwait();
            } catch (Exception ex) {
                LOG.debugf("Exception while closing connection after attach failure: %s", ex.getMessage());
            }
        }
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        var process = processes.get(connection.id());
        if (process == null || !process.isAlive()) return;
        try {
            process.getOutputStream().write(message.getBytes());
            process.getOutputStream().flush();
        } catch (IOException e) {
            LOG.debugf("Failed to write to process stdin: %s", e.getMessage());
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        cleanup(connection);
        LOG.debugf("WebSocket closed for connection %s", connection.id());
    }

    @OnError
    public void onError(Throwable throwable, WebSocketConnection connection) {
        LOG.warnf("WebSocket error for connection %s: %s", connection.id(), throwable.getMessage());
        cleanup(connection);
    }

    private void cleanup(WebSocketConnection connection) {
        var process = processes.remove(connection.id());
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }
}
