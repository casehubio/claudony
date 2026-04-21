package dev.claudony.server.expiry;

import dev.claudony.server.TmuxService;
import dev.claudony.server.model.Session;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@ApplicationScoped
public class StatusAwareExpiryPolicy implements ExpiryPolicy {

    private static final Logger LOG = Logger.getLogger(StatusAwareExpiryPolicy.class);
    private static final Set<String> SHELL_COMMANDS = Set.of("bash", "zsh", "sh", "dash", "fish");

    @Inject TmuxService tmux;

    @Override
    public String name() { return "status-aware"; }

    @Override
    public boolean isExpired(Session session, Duration timeout) {
        try {
            var currentCommand = tmux.displayMessage(session.name(), "#{pane_current_command}");
            if (currentCommand.isBlank()) return true;
            if (!SHELL_COMMANDS.contains(currentCommand.trim())) return false;
            return Duration.between(session.lastActive(), Instant.now()).compareTo(timeout) > 0;
        } catch (Exception e) {
            LOG.debugf("status-aware policy: tmux call failed for '%s': %s",
                    session.name(), e.getMessage());
            return true;
        }
    }
}
