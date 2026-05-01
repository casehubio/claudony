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
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration test that verifies system prompt is absent when
 * claudony.casehub.mesh-participation=silent is set via a test profile.
 */
@QuarkusTest
@TestProfile(SystemPromptSilentProfileTest.SilentProfile.class)
@TestSecurity(user = "test", roles = "user")
class SystemPromptSilentProfileTest {

    public static class SilentProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("claudony.casehub.mesh-participation", "silent");
        }
    }

    @Inject
    ClaudonyWorkerContextProvider provider;

    @Test
    void silentConfig_systemPromptAbsent() {
        UUID caseId = UUID.randomUUID();
        WorkerContext ctx = provider.buildContext("integration-worker", caseId,
                WorkRequest.of("researcher", Map.of()));

        assertThat(ctx.properties()).doesNotContainKey("systemPrompt");
    }
}
