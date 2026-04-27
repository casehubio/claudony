package dev.claudony;

import dev.claudony.casehub.ClaudonyWorkerContextProvider;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration test that verifies the CDI wiring for MeshParticipationStrategy
 * when claudony.casehub.mesh-participation=silent is set via a test profile.
 */
@QuarkusTest
@TestProfile(MeshParticipationSilentProfileTest.SilentProfile.class)
@TestSecurity(user = "test", roles = "user")
class MeshParticipationSilentProfileTest {

    public static class SilentProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("claudony.casehub.mesh-participation", "silent");
        }
    }

    @Inject
    ClaudonyWorkerContextProvider provider;

    @Test
    void silentConfig_stampsParticipationSilent() {
        WorkerContext ctx = provider.buildContext("integration-worker",
                WorkRequest.of("task", Map.of()));

        assertThat(ctx.properties())
                .containsEntry("meshParticipation", "SILENT");
    }
}
