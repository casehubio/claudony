package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.agent.router.ModelChainExhaustedException;
import io.casehub.platform.api.model.ModelChain;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelRegistry;
import io.casehub.platform.api.model.ModelTier;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliCircuitBreakerTest {

    private static final java.util.function.Consumer<Duration> NO_OP_SLEEPER = d -> {};

    private CliCircuitBreaker breaker(java.util.function.Function<String, Boolean> aliveCheck,
                                       java.util.function.Function<String, Integer> exitCodeCheck) {
        return new CliCircuitBreaker(aliveCheck, exitCodeCheck, NO_OP_SLEEPER, null);
    }

    private CliCircuitBreaker breakerWithRegistry(java.util.function.Function<String, Boolean> aliveCheck,
                                                    java.util.function.Function<String, Integer> exitCodeCheck,
                                                    ModelRegistry registry) {
        return new CliCircuitBreaker(aliveCheck, exitCodeCheck, NO_OP_SLEEPER, registry);
    }

    private ModelRegistry registryWith(String... modelIds) {
        return TestModelRegistries.withIds(modelIds);
    }

    @Test
    void firstEntrySucceeds_noRetry() {
        var createCount = new AtomicInteger();
        var b = breaker(sessionId -> true, sessionId -> 0);

        var result = b.tryChain(
                ModelChain.of("opus", "sonnet"),
                "claude", Map.of(), Map.of(),
                (model, command) -> {
                    createCount.incrementAndGet();
                    return "session-" + model;
                },
                Duration.ofSeconds(1));

        assertThat(result.sessionId()).isEqualTo("session-opus");
        assertThat(result.resolvedModel()).isEqualTo("opus");
        assertThat(createCount.get()).isEqualTo(1);
    }

    @Test
    void firstEntryFails_retriesSecond() {
        var createCount = new AtomicInteger();
        var b = breaker(
                sessionId -> !sessionId.contains("opus"),
                sessionId -> sessionId.contains("opus") ? 1 : 0);

        var result = b.tryChain(
                ModelChain.of("opus", "sonnet"),
                "claude", Map.of(), Map.of(),
                (model, command) -> {
                    createCount.incrementAndGet();
                    return "session-" + model;
                },
                Duration.ofSeconds(1));

        assertThat(result.sessionId()).isEqualTo("session-sonnet");
        assertThat(result.resolvedModel()).isEqualTo("sonnet");
        assertThat(result.wasFallback()).isTrue();
        assertThat(createCount.get()).isEqualTo(2);
    }

    @Test
    void allEntriesFail_throws() {
        var b = breaker(sessionId -> false, sessionId -> 1);

        assertThatThrownBy(() -> b.tryChain(
                ModelChain.of("opus", "sonnet"),
                "claude", Map.of(), Map.of(),
                (model, command) -> "session-" + model,
                Duration.ofSeconds(1)))
                .isInstanceOf(ModelChainExhaustedException.class);
    }

    @Test
    void exitCodeAbove128_throwsSessionSignalKillException() {
        var b = breaker(sessionId -> false, sessionId -> 139);

        assertThatThrownBy(() -> b.tryChain(
                ModelChain.of("opus"),
                "claude", Map.of(), Map.of(),
                (model, command) -> "session-" + model,
                Duration.ofSeconds(1)))
                .isInstanceOf(SessionSignalKillException.class)
                .satisfies(ex -> {
                    var kill = (SessionSignalKillException) ex;
                    assertThat(kill.sessionId()).isEqualTo("session-opus");
                    assertThat(kill.exitCode()).isEqualTo(139);
                    assertThat(kill.signal()).isEqualTo(11);
                });
    }

    @Test
    void zeroExitCode_treatAsSuccess() {
        var b = breaker(sessionId -> false, sessionId -> 0);

        var result = b.tryChain(
                ModelChain.of("opus"),
                "claude", Map.of(), Map.of(),
                (model, command) -> "session-" + model,
                Duration.ofSeconds(1));

        assertThat(result.sessionId()).isEqualTo("session-opus");
    }

    @Test
    void customGracePeriod_usedForEntry() {
        var b = breaker(sessionId -> true, sessionId -> 0);

        var result = b.tryChain(
                ModelChain.of("llama3"),
                "claude", Map.of("llama3", "ollama run"),
                Map.of("llama3", Duration.ofSeconds(90)),
                (model, command) -> "session-" + model,
                Duration.ofSeconds(30));

        assertThat(result.resolvedCommand()).isEqualTo("ollama run");
    }

    @Test
    void sleeperCalledWithGracePeriod() {
        var sleptDuration = new AtomicReference<Duration>();
        var b = new CliCircuitBreaker(
                sessionId -> true, sessionId -> 0,
                sleptDuration::set, null);

        b.tryChain(
                ModelChain.of("opus"),
                "claude", Map.of(), Map.of(),
                (model, command) -> "session-" + model,
                Duration.ofSeconds(42));

        assertThat(sleptDuration.get()).isEqualTo(Duration.ofSeconds(42));
    }

    @Test
    void queriedEntry_resolvedViaModelRegistry() {
        var registry = registryWith("opus");
        var b = breakerWithRegistry(sessionId -> true, sessionId -> 0, registry);

        var entries = List.<ModelChain.ModelChainEntry>of(
                new ModelChain.ModelChainEntry.Queried(ModelQuery.builder().tier(ModelTier.FLAGSHIP).build()));

        var result = b.tryChain(
                ModelChain.of(entries),
                "claude", Map.of(), Map.of(),
                (model, command) -> "session-" + model,
                Duration.ofSeconds(1));

        assertThat(result.resolvedModel()).isEqualTo("opus");
    }

    @Test
    void queriedEntry_noRegistry_skipped() {
        var b = breaker(sessionId -> true, sessionId -> 0);

        var entries = List.<ModelChain.ModelChainEntry>of(
                new ModelChain.ModelChainEntry.Queried(ModelQuery.builder().tier(ModelTier.FLAGSHIP).build()),
                new ModelChain.ModelChainEntry.Named("sonnet"));

        var result = b.tryChain(
                ModelChain.of(entries),
                "claude", Map.of(), Map.of(),
                (model, command) -> "session-" + model,
                Duration.ofSeconds(1));

        assertThat(result.resolvedModel()).isEqualTo("sonnet");
    }
}
