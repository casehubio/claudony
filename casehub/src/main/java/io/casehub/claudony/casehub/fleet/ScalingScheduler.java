package io.casehub.claudony.casehub.fleet;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@ApplicationScoped
public class ScalingScheduler {

    private static final Logger LOG = Logger.getLogger(ScalingScheduler.class.getName());

    private final AgentPoolDefinitionRegistry defRegistry;
    private final AgentPoolManagerRegistry mgrRegistry;
    private final Map<String, ScalingState> scalingStates = new ConcurrentHashMap<>();
    private final Map<String, ScalingPolicy> policyCache = new ConcurrentHashMap<>();
    private final java.util.Set<String> failedPolicyLookups = ConcurrentHashMap.newKeySet();
    private final PoolEventEmitter      eventEmitter;
    private final java.util.List<DemandMetricsSource> metricsSources;


    @Inject
    public ScalingScheduler(AgentPoolDefinitionRegistry defRegistry,
                            AgentPoolManagerRegistry mgrRegistry,
                            jakarta.enterprise.inject.Instance<PoolEventEmitter> eventEmitterInstance,
                            jakarta.enterprise.inject.Instance<DemandMetricsSource> metricsSourcesInstance) {
        this.defRegistry    = defRegistry;
        this.mgrRegistry    = mgrRegistry;
        this.eventEmitter   = eventEmitterInstance.isUnsatisfied() ? null : eventEmitterInstance.get();
        this.metricsSources = metricsSourcesInstance.stream().toList();
    }

    ScalingScheduler(AgentPoolDefinitionRegistry defRegistry,
                     AgentPoolManagerRegistry mgrRegistry) {
        this.defRegistry    = defRegistry;
        this.mgrRegistry    = mgrRegistry;
        this.eventEmitter   = null;
        this.metricsSources = java.util.List.of();
    }

    ScalingScheduler(AgentPoolDefinitionRegistry defRegistry,
                     AgentPoolManagerRegistry mgrRegistry,
                     PoolEventEmitter eventEmitter) {
        this.defRegistry    = defRegistry;
        this.mgrRegistry    = mgrRegistry;
        this.eventEmitter   = eventEmitter;
        this.metricsSources = java.util.List.of();
    }

    ScalingScheduler(AgentPoolDefinitionRegistry defRegistry,
                     AgentPoolManagerRegistry mgrRegistry,
                     PoolEventEmitter eventEmitter,
                     java.util.List<DemandMetricsSource> metricsSources) {
        this.defRegistry    = defRegistry;
        this.mgrRegistry    = mgrRegistry;
        this.eventEmitter   = eventEmitter;
        this.metricsSources = metricsSources != null ? metricsSources : java.util.List.of();
    }


    @Scheduled(every = "${claudony.scaling.interval:15s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        for (String poolName : mgrRegistry.poolNames()) {
            try {
                evaluatePool(poolName);
            } catch (Exception e) {
                LOG.warning("Scaling evaluation failed for pool '" + poolName + "': " + e.getMessage());
            }
        }
    }

    public java.util.Optional<ScalingState> scalingState(String poolName) {
        return java.util.Optional.ofNullable(scalingStates.get(poolName));
    }

    public void invalidatePolicy(String poolName) {
        policyCache.remove(poolName);
        failedPolicyLookups.remove(poolName);
    }


    private void evaluatePool(String poolName) {
        var manager    = mgrRegistry.get(poolName).orElse(null);
        var definition = defRegistry.get(poolName).orElse(null);
        if (manager == null || definition == null) {return;}

        var scalingConfig = definition.pool().scaling();
        if (scalingConfig instanceof ScalingConfig.NoScalingConfig) {return;}

        var externalMetrics = collectExternalMetrics(poolName);
        var demand        = manager.snapshotAndResetDemandMetrics(externalMetrics);
        var previousState = scalingStates.get(poolName);

        if (previousState != null && previousState.cooldownRemaining(Instant.now()).compareTo(Duration.ZERO) > 0) {
            return;
        }

        var status = manager.status();
        var snapshot = new PoolSnapshot(
                status.active(), status.idle(), status.min(), status.max(), demand);

        if (failedPolicyLookups.contains(poolName)) {return;}
        var policy = policyCache.computeIfAbsent(poolName, k -> {
            var p = createPolicy(scalingConfig);
            if (p == null) {failedPolicyLookups.add(poolName);}
            return p;
        });
        if (policy == null) {return;}

        var decision = policy.evaluate(snapshot);

        Instant now          = Instant.now();
        Instant scaleOutTime = previousState != null ? previousState.lastScaleOut() : null;
        Instant scaleInTime  = previousState != null ? previousState.lastScaleIn() : null;

        if (decision.direction() != ScalingDirection.NONE) {
            int currentMax = status.max();
            int newMax = switch (decision.direction()) {
                case OUT -> currentMax + decision.count();
                case IN -> currentMax - decision.count();
                case NONE -> currentMax;
            };
            int actualMax = manager.adjustMaxActive(newMax);
            if (actualMax != currentMax) {
                if (decision.direction() == ScalingDirection.OUT) {
                    scaleOutTime = now;
                } else if (decision.direction() == ScalingDirection.IN) {scaleInTime = now;}
                if (eventEmitter != null) {
                    eventEmitter.emitScalingDecision(poolName, decision, currentMax, actualMax);
                }
            }
        }

        scalingStates.put(poolName, new ScalingState(decision, now, scaleOutTime, scaleInTime, scalingConfig));
    }


    private java.util.Map<String, Double> collectExternalMetrics(String poolName) {
        if (metricsSources.isEmpty()) {return java.util.Map.of();}
        var merged = new java.util.HashMap<String, Double>();
        for (var source : metricsSources) {
            try {
                var metrics = source.collect(poolName);
                if (metrics != null) {merged.putAll(metrics);}
            } catch (Exception e) {
                LOG.warning("DemandMetricsSource failed for pool '" + poolName + "': " + e.getMessage());
            }
        }
        return java.util.Map.copyOf(merged);
    }

    private ScalingPolicy createPolicy(ScalingConfig config) {
        return switch (config) {
            case ScalingConfig.TargetTrackingConfig t -> new TargetTrackingPolicy(t.targetFillRatio());
            case ScalingConfig.StepConfig s -> new StepScalingPolicy(s.steps());
            case ScalingConfig.DemandPressureConfig d -> new DemandPressurePolicy(d.exhaustionThreshold(), d.latencyThresholdMs());
            case ScalingConfig.CustomScalingConfig c -> resolveCustomPolicy(c.beanName());
            case ScalingConfig.NoScalingConfig n -> new NoOpScalingPolicy();
        };
    }

    private ScalingPolicy resolveCustomPolicy(String beanName) {
        try {
            var container = io.quarkus.arc.Arc.container();
            if (container == null) {
                LOG.warning("Arc container not available — custom scaling policy '" + beanName + "' cannot be resolved");
                return null;
            }
            var handle = container.instance(ScalingPolicy.class,
                jakarta.enterprise.inject.literal.NamedLiteral.of(beanName));
            if (handle.isAvailable()) return handle.get();
            LOG.warning("Custom scaling policy bean '" + beanName + "' not found — scaling disabled for this pool");
        } catch (Exception e) {
            LOG.warning("Failed to resolve custom scaling policy bean '" + beanName + "': " + e.getMessage());
        }
        return null;
    }

}
