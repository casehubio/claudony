package io.casehub.claudony;

import io.casehub.claudony.casehub.ClaudonyReactiveWorkerContextProvider;
import io.casehub.api.model.WorkRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class SystemPromptIntegrationTest {

    @Inject
    ClaudonyReactiveWorkerContextProvider provider;

    @Test
    @RunOnVertxContext
    void defaultConfig_activeStrategy_systemPromptPresent(UniAsserter asserter) {
        UUID caseId = UUID.randomUUID();
        asserter.assertThat(
                () -> provider.buildContext("integration-worker", caseId, WorkRequest.of("researcher", Map.of())),
                ctx -> assertThat(ctx.properties()).containsKey("systemPrompt"));
    }

    @Test
    @RunOnVertxContext
    void defaultConfig_systemPromptContainsCaseId(UniAsserter asserter) {
        UUID caseId = UUID.randomUUID();
        asserter.assertThat(
                () -> provider.buildContext("integration-worker", caseId, WorkRequest.of("researcher", Map.of())),
                ctx -> assertThat((String) ctx.properties().get("systemPrompt")).contains(caseId.toString()));
    }

    @Test
    @RunOnVertxContext
    void defaultConfig_systemPromptContainsStartupSection(UniAsserter asserter) {
        UUID caseId = UUID.randomUUID();
        asserter.assertThat(
                () -> provider.buildContext("integration-worker", caseId, WorkRequest.of("researcher", Map.of())),
                ctx -> assertThat((String) ctx.properties().get("systemPrompt")).contains("STARTUP:"));
    }
}
