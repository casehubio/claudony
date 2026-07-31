package io.casehub.claudony;

import io.casehub.api.model.WorkRequest;
import io.casehub.claudony.casehub.ClaudonyWorkerContextProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

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
        var  ctx    = provider.buildContext("integration-worker", caseId, WorkRequest.of("agent", Map.of()));
        org.assertj.core.api.Assertions.assertThat(ctx.properties()).doesNotContainKey("systemPrompt");
    }
}
