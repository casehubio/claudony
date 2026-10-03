package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.agent.router.ModelChainExhaustedException;
import io.casehub.platform.api.model.ModelChain;
import io.casehub.platform.api.model.ModelRegistry;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public class CliCircuitBreaker {

    private static final Logger LOG = Logger.getLogger(CliCircuitBreaker.class);

    private final Function<String, Boolean> sessionAliveCheck;
    private final Function<String, Integer> exitCodeCheck;
    private final Consumer<Duration> sleeper;
    private final ModelRegistry modelRegistry;

    public CliCircuitBreaker(Function<String, Boolean> sessionAliveCheck,
                              Function<String, Integer> exitCodeCheck,
                              Consumer<Duration> sleeper,
                              ModelRegistry modelRegistry) {
        this.sessionAliveCheck = sessionAliveCheck;
        this.exitCodeCheck = exitCodeCheck;
        this.sleeper = sleeper;
        this.modelRegistry = modelRegistry;
    }

    public CliCircuitBreaker(Function<String, Boolean> sessionAliveCheck,
                              Function<String, Integer> exitCodeCheck) {
        this(sessionAliveCheck, exitCodeCheck, CliCircuitBreaker::threadSleep, null);
    }

    public record CircuitBreakerResult(String sessionId, String resolvedModel,
                                        String resolvedCommand, boolean wasFallback) {}

    public CircuitBreakerResult tryChain(ModelChain chain, String defaultCommand,
                                          Map<String, String> entryCommands,
                                          Map<String, Duration> gracePeriods,
                                          BiFunction<String, String, String> sessionCreator,
                                          Duration defaultGracePeriod) {
        int attempts = 0;

        for (var entry : chain.entries()) {
            String modelRef = resolveEntryToModelRef(entry);
            if (modelRef == null) continue;

            var command = entryCommands.getOrDefault(modelRef, defaultCommand);
            attempts++;

            String sessionId = sessionCreator.apply(modelRef, command);

            Duration gracePeriod = gracePeriods.getOrDefault(modelRef, defaultGracePeriod);
            sleeper.accept(gracePeriod);

            boolean alive = sessionAliveCheck.apply(sessionId);
            if (alive) {
                return new CircuitBreakerResult(sessionId, modelRef, command, attempts > 1);
            }

            int exitCode = exitCodeCheck.apply(sessionId);

            if (exitCode == 0) {
                return new CircuitBreakerResult(sessionId, modelRef, command, attempts > 1);
            }

            if (exitCode >= 128) {
                throw new RuntimeException("Session " + sessionId + " killed by signal (exit " + exitCode + ") — not model-related");
            }

            LOG.warnf("Session %s exited within grace period with exit code %d, model %s — trying next",
                      sessionId, exitCode, modelRef);
        }

        throw new ModelChainExhaustedException(chain, chain.entries());
    }

    private String resolveEntryToModelRef(ModelChain.ModelChainEntry entry) {
        return switch (entry) {
            case ModelChain.ModelChainEntry.Named n -> n.modelRef();
            case ModelChain.ModelChainEntry.Queried q -> {
                if (modelRegistry == null) yield null;
                var candidates = modelRegistry.query(q.query());
                yield candidates.isEmpty() ? null : candidates.get(0).apiModelId();
            }
        };
    }

    private static void threadSleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
