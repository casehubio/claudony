package io.casehub.claudony;

import io.casehub.claudony.casehub.ClaudonyReactiveWorkerContextProvider;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
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
    ClaudonyReactiveWorkerContextProvider provider;

    @Test
    void silentConfig_systemPromptAbsent() {
        UUID caseId = UUID.randomUUID();
        WorkerContext ctx = provider.buildContext("integration-worker", caseId,
                WorkRequest.of("researcher", Map.of()))
                .await().atMost(Duration.ofSeconds(5));

        assertThat(ctx.properties()).doesNotContainKey("systemPrompt");
    }
}
