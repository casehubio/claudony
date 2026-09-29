package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DefaultEvictionPolicyTest {

    private final EvictionPolicy policy = new DefaultEvictionPolicy();

    @Test
    void scoreIsOneWhenJustCreatedAndNoMemory() {
        var session = new ManagedSession("s1", "reviewer", "/workspace", "conv-1");
        Instant now = session.lastInteraction();
        // (0 idle + 1) * (1 + 0/100) = 1.0
        assertThat(policy.score(session, now)).isCloseTo(1.0, within(0.001));
    }

    @Test
    void scoreIncreasesWithIdleTime() {
        var session = new ManagedSession("s1", "reviewer", "/workspace", "conv-1");
        Instant now = session.lastInteraction().plusSeconds(60);
        // (60 + 1) * (1 + 0/100) = 61.0
        assertThat(policy.score(session, now)).isCloseTo(61.0, within(0.001));
    }

    @Test
    void scoreIncreasesWithMemory() {
        var session = new ManagedSession("s1", "reviewer", "/workspace", "conv-1");
        session.recordInteraction(100 * 1024 * 1024); // 100 MB
        Instant now = session.lastInteraction();
        double memoryMB = 100.0;
        // (0 + 1) * (1 + 100/100) = 2.0
        assertThat(policy.score(session, now)).isCloseTo(2.0, within(0.001));
    }

    @Test
    void scoreCombinesIdleTimeAndMemory() {
        var session = new ManagedSession("s1", "reviewer", "/workspace", "conv-1");
        session.recordInteraction(200 * 1024 * 1024); // 200 MB
        Instant now = session.lastInteraction().plusSeconds(30);
        // (30 + 1) * (1 + 200/100) = 31 * 3 = 93.0
        assertThat(policy.score(session, now)).isCloseTo(93.0, within(0.001));
    }

    @Test
    void higherMemoryProducesHigherScoreThanLongerIdle() {
        var lowMemory = new ManagedSession("s1", "reviewer", "/ws1", "conv-1");
        lowMemory.recordInteraction(10 * 1024 * 1024); // 10 MB
        var highMemory = new ManagedSession("s2", "coder", "/ws2", "conv-2");
        highMemory.recordInteraction(500 * 1024 * 1024); // 500 MB

        Instant now = lowMemory.lastInteraction().plusSeconds(100);
        // lowMemory: (100+1) * (1 + 10/100) = 101 * 1.1 = 111.1
        // highMemory: (100+1) * (1 + 500/100) = 101 * 6.0 = 606.0
        assertThat(policy.score(highMemory, now))
                .isGreaterThan(policy.score(lowMemory, now));
    }
}
