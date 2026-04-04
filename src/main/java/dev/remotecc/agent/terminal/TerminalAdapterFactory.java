package dev.remotecc.agent.terminal;

import dev.remotecc.config.RemoteCCConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TerminalAdapterFactory {

    private static final Logger LOG = Logger.getLogger(TerminalAdapterFactory.class);

    @Inject RemoteCCConfig config;
    @Inject ITerm2Adapter iterm2;

    private List<TerminalAdapter> all() {
        return List.of(iterm2);
    }

    public Optional<TerminalAdapter> resolve() {
        return resolveForConfig(config.terminal());
    }

    public Optional<TerminalAdapter> resolveForConfig(String terminal) {
        return switch (terminal) {
            case "none" -> Optional.empty();
            case "auto" -> all().stream()
                    .filter(TerminalAdapter::isAvailable)
                    .findFirst()
                    .map(a -> { LOG.infof("Auto-detected terminal: %s", a.name()); return a; });
            default -> all().stream().filter(a -> a.name().equals(terminal)).findFirst();
        };
    }
}
