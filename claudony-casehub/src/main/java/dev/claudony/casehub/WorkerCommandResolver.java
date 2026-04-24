package dev.claudony.casehub;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class WorkerCommandResolver {

    private final Map<String, String> capabilityToCommand;

    @Inject
    public WorkerCommandResolver(CaseHubConfig config) {
        this(config.workers().commands());
    }

    WorkerCommandResolver(Map<String, String> capabilityToCommand) {
        this.capabilityToCommand = Map.copyOf(capabilityToCommand);
    }

    public String resolve(Set<String> requiredCapabilities) {
        for (String cap : requiredCapabilities) {
            String cmd = capabilityToCommand.get(cap);
            if (cmd != null) return cmd;
        }
        String defaultCmd = capabilityToCommand.get("default");
        if (defaultCmd == null) {
            throw new IllegalStateException(
                    "no command configured for capabilities " + requiredCapabilities +
                    " — add claudony.casehub.workers.commands.default");
        }
        return defaultCmd;
    }

    public Set<String> getAvailableCapabilities() {
        return capabilityToCommand.keySet().stream()
                .filter(k -> !k.equals("default"))
                .collect(Collectors.toUnmodifiableSet());
    }
}
