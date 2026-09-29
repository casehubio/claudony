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
        return create(identity, workingDir, defaultCommand);
    }

    @Override
    public String create(String identity, String workingDir, String command) {
        String sessionId        = sessionPrefix + UUID.randomUUID().toString().substring(0, 8);
        String conversationUuid = UUID.randomUUID().toString();
        String fullCommand      = command + " --session-id " + conversationUuid;
        try {
            tmux.createWorkerSession(sessionId, workingDir, fullCommand);
            tmux.setSessionOption(sessionId, "@claudony_identity", identity);
            tmux.setSessionOption(sessionId, "@claudony_conversation_id", conversationUuid);
            tmux.setSessionOption(sessionId, "@claudony_working_dir", workingDir);
            tmux.setSessionOption(sessionId, "@claudony_state", "active");
            conversationIds.put(sessionId, conversationUuid);
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
            // Set remain-on-exit BEFORE killing the process so the pane survives
            tmux.setSessionOption(sessionId, "remain-on-exit", "on");
            long pid = tmux.panePid(sessionId);
            if (pid > 0) {
                new ProcessBuilder("kill", String.valueOf(pid)).start().waitFor();
            }
            tmux.setSessionOption(sessionId, "@claudony_state", "suspended");
        } catch (IOException | InterruptedException e) {
            LOG.debugf("Error suspending session %s: %s", sessionId, e.getMessage());
        }
    }

    @Override
    public void resume(String sessionId, String conversationId, String workingDir) {
        String command = conversationId != null
                         ? defaultCommand + " -r " + conversationId
                         : defaultCommand;
        try {
            tmux.respawnPane(sessionId, command);
            tmux.setSessionOption(sessionId, "remain-on-exit", "off");
            tmux.setSessionOption(sessionId, "@claudony_state", "active");
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

    public void bootstrapFromTmux(AgentSessionManager manager) {
        try {
            for (String name : tmux.listSessionNames()) {
                if (!name.startsWith(sessionPrefix)) {continue;}
                var stateOpt = tmux.getSessionOption(name, "@claudony_state");
                if (stateOpt.isEmpty()) {continue;}

                var identityOpt     = tmux.getSessionOption(name, "@claudony_identity");
                var conversationOpt = tmux.getSessionOption(name, "@claudony_conversation_id");
                var workingDirOpt   = tmux.getSessionOption(name, "@claudony_working_dir");

                String identity   = identityOpt.orElse("unknown");
                String convId     = conversationOpt.orElse(null);
                String workingDir = workingDirOpt.orElse("");
                SessionState state = "suspended".equals(stateOpt.get())
                                     ? SessionState.SUSPENDED : SessionState.ACTIVE;

                if (convId != null) {
                    conversationIds.put(name, convId);
                }
                manager.registerBootstrapped(name, identity, workingDir, convId, state);
                LOG.infof("Bootstrapped pool session %s (identity=%s, state=%s)", name, identity, state);
            }
        } catch (IOException | InterruptedException e) {
            LOG.warnf("Could not bootstrap pool from tmux: %s", e.getMessage());
        }
    }

}
