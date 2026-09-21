package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.server.TmuxService;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TmuxSessionOperations implements SessionOperations {

    private static final Logger LOG = Logger.getLogger(TmuxSessionOperations.class);

    private final TmuxService tmux;
    private final String sessionPrefix;
    private final String defaultCommand;
    private final ConcurrentHashMap<String, String> conversationIds = new ConcurrentHashMap<>();

    public TmuxSessionOperations(TmuxService tmux, String sessionPrefix, String defaultCommand) {
        this.tmux = tmux;
        this.sessionPrefix = sessionPrefix;
        this.defaultCommand = defaultCommand;
    }

    @Override
    public String create(String identity, String workingDir) {
        String sessionId = sessionPrefix + UUID.randomUUID().toString().substring(0, 8);
        try {
            tmux.createWorkerSession(sessionId, workingDir, defaultCommand);
            tmux.setSessionOption(sessionId, "@claudony_identity", identity);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to create session for identity " + identity, e);
        }
        return sessionId;
    }

    @Override
    public String conversationId(String sessionId) {
        return conversationIds.get(sessionId);
    }

    public void setConversationId(String sessionId, String conversationId) {
        conversationIds.put(sessionId, conversationId);
    }

    @Override
    public void suspend(String sessionId) {
        try {
            tmux.killSession(sessionId);
        } catch (IOException | InterruptedException e) {
            LOG.debugf("Session %s already gone on suspend: %s", sessionId, e.getMessage());
        }
    }

    @Override
    public void resume(String sessionId, String conversationId, String workingDir) {
        String command = conversationId != null
                ? defaultCommand + " -c " + conversationId
                : defaultCommand;
        try {
            tmux.createWorkerSession(sessionId, workingDir, command);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to resume session " + sessionId, e);
        }
    }

    @Override
    public void destroy(String sessionId) {
        conversationIds.remove(sessionId);
        try {
            tmux.killSession(sessionId);
        } catch (IOException | InterruptedException e) {
            LOG.debugf("Session %s already gone on destroy: %s", sessionId, e.getMessage());
        }
    }

    @Override
    public long memoryBytes(String sessionId) {
        try {
            String pidStr = tmux.displayMessage(sessionId, "#{pane_pid}");
            if (pidStr == null || pidStr.isBlank()) return 0;
            var p = new ProcessBuilder("ps", "-o", "rss=", "-p", pidStr.trim())
                    .redirectErrorStream(true).start();
            String rssKb = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (rssKb.isBlank()) return 0;
            return Long.parseLong(rssKb) * 1024;
        } catch (IOException | InterruptedException | NumberFormatException e) {
            LOG.debugf("Could not read memory for session %s: %s", sessionId, e.getMessage());
            return 0;
        }
    }
}
