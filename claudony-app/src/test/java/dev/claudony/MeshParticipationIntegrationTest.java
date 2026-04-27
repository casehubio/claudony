package dev.claudony;

import dev.claudony.casehub.ClaudonyWorkerContextProvider;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
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
    ClaudonyWorkerContextProvider provider;

    @Test
    void defaultConfig_stampsActiveParticipation() {
        WorkerContext ctx = provider.buildContext("integration-worker",
                WorkRequest.of("task", Map.of()));

        assertThat(ctx.properties())
                .containsEntry("meshParticipation", "ACTIVE");
    }

    @Test
    void defaultConfig_meshParticipationKeyAlwaysPresent() {
        WorkerContext ctx = provider.buildContext("integration-worker",
                WorkRequest.of("researcher", Map.of("caseId", "not-a-uuid")));

        assertThat(ctx.properties()).containsKey("meshParticipation");
    }
}
