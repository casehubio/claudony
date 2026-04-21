package dev.claudony.server.expiry;

import dev.claudony.server.TmuxService;
import dev.claudony.server.model.Session;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class TerminalOutputExpiryPolicy implements ExpiryPolicy {

    private static final Logger LOG = Logger.getLogger(TerminalOutputExpiryPolicy.class);

    @Inject TmuxService tmux;

    @Override
    public String name() { return "terminal-output"; }

    @Override
    public boolean isExpired(Session session, Duration timeout) {
        try {
            // #{pane_activity} is always blank in tmux 3.6a without a client;
            // #{window_activity} is the correct equivalent for measuring terminal inactivity
            var raw = tmux.displayMessage(session.name(), "#{window_activity}");
            if (raw.isBlank()) return true;
            var lastActivity = Instant.ofEpochSecond(Long.parseLong(raw.trim()));
            return Duration.between(lastActivity, Instant.now()).compareTo(timeout) > 0;
        } catch (Exception e) {
            LOG.debugf("terminal-output policy: tmux call failed for '%s': %s",
                    session.name(), e.getMessage());
            return true;
        }
    }
}
