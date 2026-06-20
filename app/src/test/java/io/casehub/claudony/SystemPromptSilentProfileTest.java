package io.casehub.claudony;

import io.casehub.claudony.casehub.ClaudonyReactiveWorkerContextProvider;
import io.casehub.api.model.WorkRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
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
    ClaudonyReactiveWorkerContextProvider provider;

    @Test
    @RunOnVertxContext
    void silentConfig_systemPromptAbsent(UniAsserter asserter) {
        UUID caseId = UUID.randomUUID();
        asserter.assertThat(
                () -> provider.buildContext("integration-worker", caseId, WorkRequest.of("agent", Map.of())),
                ctx -> assertThat(ctx.properties()).doesNotContainKey("systemPrompt"));
    }
}
