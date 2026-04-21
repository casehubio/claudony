package dev.claudony.server.expiry;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ExpiryPolicyRegistryTest {

    @Inject ExpiryPolicyRegistry registry;

    @Test
    void resolvesUserInteractionPolicy() {
        assertThat(registry.resolve("user-interaction").name()).isEqualTo("user-interaction");
    }

    @Test
    void resolvesTerminalOutputPolicy() {
        assertThat(registry.resolve("terminal-output").name()).isEqualTo("terminal-output");
    }

    @Test
    void resolvesStatusAwarePolicy() {
        assertThat(registry.resolve("status-aware").name()).isEqualTo("status-aware");
    }

    @Test
    void resolvesNullToDefault() {
        assertThat(registry.resolve(null).name()).isEqualTo("user-interaction");
    }

    @Test
    void resolvesUnknownNameToDefault() {
        assertThat(registry.resolve("no-such-policy").name()).isEqualTo("user-interaction");
    }

    @Test
    void availableNamesContainsAllThreePolicies() {
        assertThat(registry.availableNames())
                .containsExactlyInAnyOrder("user-interaction", "terminal-output", "status-aware");
    }
}
