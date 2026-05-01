package dev.claudony;

import dev.claudony.casehub.ClaudonyWorkerContextProvider;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkerContext;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class SystemPromptIntegrationTest {

    @Inject
    ClaudonyWorkerContextProvider provider;

    @Test
    void defaultConfig_activeStrategy_systemPromptPresent() {
        UUID caseId = UUID.randomUUID();
        WorkerContext ctx = provider.buildContext("integration-worker", caseId,
                WorkRequest.of("researcher", Map.of()));

        assertThat(ctx.properties()).containsKey("systemPrompt");
    }

    @Test
    void defaultConfig_systemPromptContainsCaseId() {
        UUID caseId = UUID.randomUUID();
        WorkerContext ctx = provider.buildContext("integration-worker", caseId,
                WorkRequest.of("researcher", Map.of()));

        String prompt = (String) ctx.properties().get("systemPrompt");
        assertThat(prompt).contains(caseId.toString());
    }

    @Test
    void defaultConfig_systemPromptContainsStartupSection() {
        UUID caseId = UUID.randomUUID();
        WorkerContext ctx = provider.buildContext("integration-worker", caseId,
                WorkRequest.of("researcher", Map.of()));

        String prompt = (String) ctx.properties().get("systemPrompt");
        assertThat(prompt).contains("STARTUP:");
    }
}
