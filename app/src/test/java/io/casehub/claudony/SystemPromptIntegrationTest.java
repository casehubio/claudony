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
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class SystemPromptIntegrationTest {

    @Inject
    ClaudonyReactiveWorkerContextProvider provider;

    @Test
    void defaultConfig_activeStrategy_systemPromptPresent() {
        UUID caseId = UUID.randomUUID();
        WorkerContext ctx = provider.buildContext("integration-worker", caseId,
                WorkRequest.of("researcher", Map.of()))
                .await().atMost(Duration.ofSeconds(5));

        assertThat(ctx.properties()).containsKey("systemPrompt");
    }

    @Test
    void defaultConfig_systemPromptContainsCaseId() {
        UUID caseId = UUID.randomUUID();
        WorkerContext ctx = provider.buildContext("integration-worker", caseId,
                WorkRequest.of("researcher", Map.of()))
                .await().atMost(Duration.ofSeconds(5));

        String prompt = (String) ctx.properties().get("systemPrompt");
        assertThat(prompt).contains(caseId.toString());
    }

    @Test
    void defaultConfig_systemPromptContainsStartupSection() {
        UUID caseId = UUID.randomUUID();
        WorkerContext ctx = provider.buildContext("integration-worker", caseId,
                WorkRequest.of("researcher", Map.of()))
                .await().atMost(Duration.ofSeconds(5));

        String prompt = (String) ctx.properties().get("systemPrompt");
        assertThat(prompt).contains("STARTUP:");
    }
}
