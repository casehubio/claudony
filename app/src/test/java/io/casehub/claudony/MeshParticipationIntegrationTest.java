package io.casehub.claudony;

import io.casehub.claudony.casehub.ClaudonyReactiveWorkerContextProvider;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests that verify the CDI wiring for MeshParticipationStrategy
 * with the default configuration (claudony.casehub.mesh-participation=active).
 */
@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class MeshParticipationIntegrationTest {

    @Inject
    ClaudonyReactiveWorkerContextProvider provider;

    @Test
    void defaultConfig_stampsActiveParticipation() {
        WorkerContext ctx = provider.buildContext("integration-worker", null,
                WorkRequest.of("task", Map.of()))
                .await().atMost(Duration.ofSeconds(5));

        assertThat(ctx.properties())
                .containsEntry("meshParticipation", "ACTIVE");
    }

    @Test
    void defaultConfig_meshParticipationKeyAlwaysPresent() {
        WorkerContext ctx = provider.buildContext("integration-worker", null,
                WorkRequest.of("researcher", Map.of()))
                .await().atMost(Duration.ofSeconds(5));

        assertThat(ctx.properties()).containsKey("meshParticipation");
    }
}
