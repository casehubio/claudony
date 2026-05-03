package dev.claudony.casehub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudonyInstanceActorIdProviderTest {

    private WorkerSessionMapping mapping;
    private ClaudonyInstanceActorIdProvider provider;

    @BeforeEach
    void setUp() {
        mapping = new WorkerSessionMapping();
        provider = new ClaudonyInstanceActorIdProvider();
        provider.workerSessionMapping = mapping;
    }

    @Test
    void knownWorkerSession_returnsMappedActorId() {
        String sessionId = UUID.randomUUID().toString();
        mapping.register("code-reviewer", null, sessionId);

        String result = provider.resolve("claudony-worker-" + sessionId);

        assertThat(result).isEqualTo("claude:code-reviewer@v1");
    }

    @Test
    void unknownSession_returnsInstanceIdUnchanged() {
        String instanceId = "claudony-worker-" + UUID.randomUUID();

        assertThat(provider.resolve(instanceId)).isEqualTo(instanceId);
    }

    @Test
    void terminatedWorker_returnsInstanceIdUnchanged() {
        String sessionId = UUID.randomUUID().toString();
        mapping.register("analyst", null, sessionId);
        mapping.remove("analyst");

        String instanceId = "claudony-worker-" + sessionId;
        assertThat(provider.resolve(instanceId)).isEqualTo(instanceId);
    }

    @Test
    void nonClaudonyInstanceId_passesThrough() {
        assertThat(provider.resolve("human:alice")).isEqualTo("human:alice");
        assertThat(provider.resolve("some-other-agent")).isEqualTo("some-other-agent");
    }

    @Test
    void roleNamePreservedExactly() {
        String sessionId = UUID.randomUUID().toString();
        mapping.register("senior-code-reviewer", null, sessionId);

        assertThat(provider.resolve("claudony-worker-" + sessionId))
                .isEqualTo("claude:senior-code-reviewer@v1");
    }
}
