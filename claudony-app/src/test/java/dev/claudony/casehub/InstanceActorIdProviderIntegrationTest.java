package dev.claudony.casehub;

import io.casehub.qhorus.api.spi.InstanceActorIdProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ClaudonyInstanceActorIdProvider} is selected as the active
 * {@link InstanceActorIdProvider} in the full CDI container — i.e. that the
 * {@code @Alternative @Priority(1)} annotation overrides {@code DefaultInstanceActorIdProvider}.
 */
@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class InstanceActorIdProviderIntegrationTest {

    @Inject
    InstanceActorIdProvider provider;

    @Inject
    WorkerSessionMapping workerSessionMapping;

    @Test
    void claudonyProviderIsSelected() {
        assertThat(provider).isInstanceOf(ClaudonyInstanceActorIdProvider.class);
    }

    @Test
    void knownWorkerSession_returnsMappedActorId() {
        String sessionId = UUID.randomUUID().toString();
        workerSessionMapping.register("integration-analyst", null, sessionId);
        try {
            assertThat(provider.resolve("claudony-worker-" + sessionId))
                    .isEqualTo("claude:integration-analyst@v1");
        } finally {
            workerSessionMapping.remove("integration-analyst");
        }
    }

    @Test
    void nonClaudonyInstanceId_passesThrough() {
        assertThat(provider.resolve("human:alice")).isEqualTo("human:alice");
    }
}
