package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelFallbackEventTest {

    @Test
    void recordFieldsAccessible() {
        var event = new ModelFallbackEvent("code-reviewer", "opus", "sonnet", 2);
        assertThat(event.poolName()).isEqualTo("code-reviewer");
        assertThat(event.requestedModel()).isEqualTo("opus");
        assertThat(event.resolvedModel()).isEqualTo("sonnet");
        assertThat(event.fallbackDepth()).isEqualTo(2);
    }

    @Test
    void equality() {
        var a = new ModelFallbackEvent("pool", "opus", "sonnet", 1);
        var b = new ModelFallbackEvent("pool", "opus", "sonnet", 1);
        assertThat(a).isEqualTo(b);
    }
}
