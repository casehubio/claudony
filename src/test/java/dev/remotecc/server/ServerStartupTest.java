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

    @Inject
    ServerStartup serverStartup;

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

    @Test
    void bootstrapRegistryPicksUpTmuxSessionsCreatedAfterStartup() throws Exception {
        var sessionName = "remotecc-bootstrap-test-" + System.currentTimeMillis();
        tmux.createSession(sessionName, System.getProperty("user.home"), "bash");

        try {
            // Verify NOT in registry yet (created after Quarkus startup)
            var beforeNames = registry.all().stream().map(s -> s.name()).toList();
            assertFalse(beforeNames.contains(sessionName),
                "Session created post-startup should not be in registry before bootstrap. Registry: " + beforeNames);

            // Run bootstrap — idempotent (register uses put, won't duplicate)
            serverStartup.bootstrapRegistry();

            // Now it should be in the registry
            var afterNames = registry.all().stream().map(s -> s.name()).toList();
            assertTrue(afterNames.contains(sessionName),
                "Session should be in registry after bootstrap. Registry: " + afterNames);
        } finally {
            // Clean up registry entry
            registry.all().stream()
                .filter(s -> s.name().equals(sessionName))
                .map(s -> s.id())
                .findFirst()
                .ifPresent(registry::remove);
            if (tmux.sessionExists(sessionName)) tmux.killSession(sessionName);
        }
    }
}
