package dev.claudony.casehub;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MeshParticipationStrategyTest {

    // ── Happy path ─────────────────────────────────────────────────────────

    @Test
    void active_returnsActive() {
        assertThat(new ActiveParticipationStrategy().strategyFor("worker-1", null))
                .isEqualTo(MeshParticipationStrategy.MeshParticipation.ACTIVE);
    }

    @Test
    void reactive_returnsReactive() {
        assertThat(new ReactiveParticipationStrategy().strategyFor("worker-1", null))
                .isEqualTo(MeshParticipationStrategy.MeshParticipation.REACTIVE);
    }

    @Test
    void silent_returnsSilent() {
        assertThat(new SilentParticipationStrategy().strategyFor("worker-1", null))
                .isEqualTo(MeshParticipationStrategy.MeshParticipation.SILENT);
    }

    @Test
    void threeDistinctValues() {
        assertThat(new ActiveParticipationStrategy().strategyFor("w", null))
                .isNotEqualTo(new ReactiveParticipationStrategy().strategyFor("w", null));
        assertThat(new ReactiveParticipationStrategy().strategyFor("w", null))
                .isNotEqualTo(new SilentParticipationStrategy().strategyFor("w", null));
    }

    // ── Robustness ────────────────────────────────────────────────────────

    @Test
    void allStrategiesAcceptNullWorkerId() {
        assertThatNoException().isThrownBy(() -> {
            new ActiveParticipationStrategy().strategyFor(null, null);
            new ReactiveParticipationStrategy().strategyFor(null, null);
            new SilentParticipationStrategy().strategyFor(null, null);
        });
    }

    @Test
    void allStrategiesAcceptEmptyWorkerId() {
        assertThatNoException().isThrownBy(() -> {
            new ActiveParticipationStrategy().strategyFor("", null);
            new ReactiveParticipationStrategy().strategyFor("", null);
            new SilentParticipationStrategy().strategyFor("", null);
        });
    }

    @Test
    void allStrategiesAcceptNullContext() {
        assertThatNoException().isThrownBy(() -> {
            new ActiveParticipationStrategy().strategyFor("w", null);
            new ReactiveParticipationStrategy().strategyFor("w", null);
            new SilentParticipationStrategy().strategyFor("w", null);
        });
    }

    // ── Correctness ───────────────────────────────────────────────────────

    @Test
    void allStrategiesIgnoreWorkerId() {
        var active = new ActiveParticipationStrategy();
        assertThat(active.strategyFor("alice", null)).isEqualTo(active.strategyFor("bob", null));

        var silent = new SilentParticipationStrategy();
        assertThat(silent.strategyFor("alice", null)).isEqualTo(silent.strategyFor("bob", null));
    }

    @Test
    void resultsAreConsistentAcrossRepeatedCalls() {
        var strategy = new ActiveParticipationStrategy();
        MeshParticipationStrategy.MeshParticipation first = strategy.strategyFor("w", null);
        MeshParticipationStrategy.MeshParticipation second = strategy.strategyFor("w", null);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void participationEnumHasExactlyThreeValues() {
        assertThat(MeshParticipationStrategy.MeshParticipation.values()).hasSize(3);
    }

    @Test
    void allThreeEnumValuesAreDistinct() {
        var values = MeshParticipationStrategy.MeshParticipation.values();
        assertThat(values).doesNotHaveDuplicates();
    }

    @Test
    void enumNameMatchesExpectedStrings() {
        assertThat(MeshParticipationStrategy.MeshParticipation.ACTIVE.name()).isEqualTo("ACTIVE");
        assertThat(MeshParticipationStrategy.MeshParticipation.REACTIVE.name()).isEqualTo("REACTIVE");
        assertThat(MeshParticipationStrategy.MeshParticipation.SILENT.name()).isEqualTo("SILENT");
    }
}
