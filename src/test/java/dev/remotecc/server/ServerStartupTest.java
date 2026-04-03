package dev.remotecc.server;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ServerStartupTest {

    @Inject
    SessionRegistry registry;

    @Inject
    TmuxService tmux;

    @Test
    void registryIsBootstrappedFromTmuxOnStartup() throws Exception {
        var tmuxNames = tmux.listSessionNames();
        var registryNames = registry.all().stream().map(s -> s.name()).toList();
        tmuxNames.stream()
                .filter(n -> n.startsWith("remotecc-"))
                .forEach(name ->
                    assertTrue(registryNames.contains(name),
                        "Expected registry to contain tmux session: " + name));
    }
}
