package io.casehub.claudony.casehub.fleet.script;

import io.casehub.claudony.casehub.fleet.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PoolNodeHandlerTest {

    private AgentPoolDefinitionRegistry defRegistry;
    private AgentPoolManagerRegistry mgrRegistry;
    private PoolNodeHandler handler;

    @BeforeEach
    void setUp() {
        defRegistry = new AgentPoolDefinitionRegistry();
        mgrRegistry = new AgentPoolManagerRegistry();
        SessionOperations ops = new SessionOperations() {
            @Override public String create(String identity, String workingDir) {
                return "test-" + UUID.randomUUID().toString().substring(0, 8);
            }
            @Override public String conversationId(String sessionId) { return UUID.randomUUID().toString(); }
            @Override public void suspend(String sessionId) {}
            @Override public void resume(String sessionId, String conversationId, String workingDir) {}
            @Override public void destroy(String sessionId) {}
            @Override public long memoryBytes(String sessionId) { return 0; }
        };
        handler = new PoolNodeHandler(defRegistry, mgrRegistry, ops);
    }

    @Test
    void type() {
        assertThat(handler.type()).isEqualTo("pool");
    }

    @Test
    void handlesFullSpec() {
        var spec = Map.<String, Object>of(
                "agentId", "code-reviewer",
                "minActive", 2,
                "maxActive", 8,
                "workingDir", "/workspace/reviews",
                "workingDirPolicy", "SHARED_READ",
                "eviction", "MEMORY_WEIGHTED"
        );
        var result = handler.handle("code-reviewer-pool", spec);
        assertThat(result.success()).isTrue();
        assertThat(mgrRegistry.poolNames()).contains("code-reviewer");
        assertThat(defRegistry.get("code-reviewer")).isPresent();
        var def = defRegistry.get("code-reviewer").get();
        assertThat(def.pool().minActive()).isEqualTo(2);
        assertThat(def.pool().maxActive()).isEqualTo(8);
        assertThat(def.agent().workingDir()).isEqualTo("/workspace/reviews");
        assertThat(def.agent().policy()).isEqualTo(WorkingDirPolicy.SHARED_READ);
    }

    @Test
    void handlesMinimalSpec() {
        var spec = Map.<String, Object>of("agentId", "simple");
        var result = handler.handle("simple-pool", spec);
        assertThat(result.success()).isTrue();
        assertThat(defRegistry.get("simple")).isPresent();
        var def = defRegistry.get("simple").get();
        assertThat(def.pool().minActive()).isEqualTo(0);
        assertThat(def.pool().maxActive()).isEqualTo(10);
    }

    @Test
    void handlesScalingSpec() {
        var spec = Map.<String, Object>of(
                "agentId", "scaled",
                "scaling", Map.of("type", "target-tracking", "target", 0.7)
        );
        var result = handler.handle("scaled-pool", spec);
        assertThat(result.success()).isTrue();
        var def = defRegistry.get("scaled").get();
        assertThat(def.pool().scaling()).isInstanceOf(ScalingConfig.TargetTrackingConfig.class);
    }

    @Test
    void failsOnMissingAgentId() {
        var spec = Map.<String, Object>of("minActive", 1);
        var result = handler.handle("bad-pool", spec);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("agentId");
    }
}
