package io.casehub.claudony.server.fleet;

import io.casehub.claudony.casehub.fleet.AgentPoolDefinition;
import io.casehub.claudony.casehub.fleet.AgentPoolDefinitionRegistry;
import io.casehub.claudony.casehub.fleet.AgentPoolManagerRegistry;
import io.casehub.claudony.casehub.fleet.ScalingConfig;
import io.casehub.claudony.casehub.fleet.ScalingScheduler;
import io.casehub.claudony.casehub.fleet.ScalingState;
import io.casehub.claudony.casehub.fleet.ScalingStep;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class PoolService {

    private final AgentPoolManagerRegistry mgrRegistry;
    private final AgentPoolDefinitionRegistry defRegistry;
    private final ScalingScheduler scalingScheduler;
    private final MeterRegistry meterRegistry;
    private final io.casehub.claudony.casehub.fleet.BudgetTracker budgetTracker;

    private static final com.fasterxml.jackson.databind.ObjectMapper SNAPSHOT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Inject
    public PoolService(AgentPoolDefinitionRegistry defRegistry,
                       AgentPoolManagerRegistry mgrRegistry,
                       ScalingScheduler scalingScheduler,
                       MeterRegistry meterRegistry,
                       io.casehub.claudony.casehub.fleet.BudgetTracker budgetTracker) {
        this.defRegistry = defRegistry;
        this.mgrRegistry = mgrRegistry;
        this.scalingScheduler = scalingScheduler;
        this.meterRegistry = meterRegistry;
        this.budgetTracker = budgetTracker;
    }

    public void validatePoolExists(String name) {
        mgrRegistry.get(name)
                .orElseThrow(() -> new NotFoundException("Pool not found: " + name));
    }

    public List<PoolSummary> listPools() {
        return mgrRegistry.poolNames().stream()
                .map(name -> mgrRegistry.get(name).map(mgr -> {
                    var scalingType = defRegistry.get(name)
                            .map(d -> d.pool().scaling().type())
                            .orElse("none");
                    return new PoolSummary(name, mgr.status(), scalingType);
                }).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public PoolDetail getPool(String name) {
        var mgr = mgrRegistry.get(name)
                .orElseThrow(() -> new NotFoundException("Pool not found: " + name));
        var def = defRegistry.get(name).orElse(null);
        var scalingState = scalingScheduler.scalingState(name).orElse(null);
        var definitionView = def != null
                ? new PoolDetail.DefinitionView(def.agent(),
                new PoolDetail.PoolConfigView(def.pool().minActive(), def.pool().maxActive(),
                        def.pool().eviction().name()))
                : null;
        var scalingView = buildScalingView(scalingState, def);
        var demandView = buildDemandView(name);
        var budgetView = buildBudgetView(name, def);
        return new PoolDetail(name, mgr.status(), definitionView, scalingView, demandView, budgetView);
    }

    public List<ManagedSessionInfo> listSessions(String name) {
        var mgr = mgrRegistry.get(name)
                .orElseThrow(() -> new NotFoundException("Pool not found: " + name));
        var now = Instant.now();
        return mgr.sessions().stream().map(s -> new ManagedSessionInfo(
                s.instanceId(), s.identity(), s.workingDir(),
                s.conversationId(), s.state().name(),
                s.lastInteraction(), s.lastMemoryBytes(),
                Duration.between(s.lastInteraction(), now).toSeconds()
        )).toList();
    }

    public PoolDetail updatePool(String name, PoolUpdateRequest request) {
        var mgr = mgrRegistry.get(name)
                .orElseThrow(() -> new NotFoundException("Pool not found: " + name));
        if (request.maxActive() != null) {
            mgr.adjustMaxActive(request.maxActive());
        }
        if (request.minActive() != null || request.maxActive() != null) {
            int min = request.minActive() != null ? request.minActive() : mgr.status().min();
            int max = request.maxActive() != null ? request.maxActive() : mgr.status().max();
            if (defRegistry.get(name).isPresent()) {
                defRegistry.updateCapacity(name, min, max);
            }
        }
        if (request.scalingType() != null) {
            var newConfig = parseScalingConfig(request);
            if (defRegistry.get(name).isPresent()) {
                defRegistry.updateScaling(name, newConfig);
            }
            scalingScheduler.invalidatePolicy(name);
        }
        if (hasBudgetFields(request)) {
            var budgetConfig = parseBudgetConfig(request, name);
            if (defRegistry.get(name).isPresent()) {
                defRegistry.updateBudget(name, budgetConfig);
            }
            budgetTracker.invalidate(name);
        }
        return getPool(name);
    }

    public void suspendSession(String name, String id) {
        var mgr = mgrRegistry.get(name)
                .orElseThrow(() -> new NotFoundException("Pool not found: " + name));
        mgr.suspendSession(id);
    }

    public void resumeSession(String name, String id) {
        var mgr = mgrRegistry.get(name)
                .orElseThrow(() -> new NotFoundException("Pool not found: " + name));
        mgr.resumeSession(id);
    }

    public void destroySession(String name, String id) {
        var mgr = mgrRegistry.get(name)
                .orElseThrow(() -> new NotFoundException("Pool not found: " + name));
        mgr.destroySession(id);
    }

    public String buildPoolSnapshot(String name) {
        try {
            var detail = getPool(name);
            var sessions = listSessions(name);
            return SNAPSHOT_MAPPER.writeValueAsString(java.util.Map.of("detail", detail, "sessions", sessions));
        } catch (Exception e) {
            return "{}";
        }
    }

    ScalingConfig parseScalingConfig(PoolUpdateRequest u) {
        var cooldown = u.cooldown() != null ? parseDuration(u.cooldown()) : null;
        var scaleInCooldown = u.scaleInCooldown() != null ? parseDuration(u.scaleInCooldown()) : null;
        return switch (u.scalingType()) {
            case "target-tracking" -> new ScalingConfig.TargetTrackingConfig(
                    u.targetFillRatio() != null ? u.targetFillRatio() : 0.7, cooldown, scaleInCooldown);
            case "step" -> {
                if (u.steps() == null || u.steps().isEmpty()) {
                    throw new IllegalArgumentException("steps must not be empty for step scaling");
                }
                var steps = u.steps().stream()
                        .map(s -> new ScalingStep(s.threshold(), s.adjustment()))
                        .toList();
                yield new ScalingConfig.StepConfig(steps, cooldown, scaleInCooldown);
            }
            case "demand-pressure" -> {
                if (u.exhaustionThreshold() == null || u.latencyThresholdMs() == null) {
                    throw new IllegalArgumentException(
                            "exhaustionThreshold and latencyThresholdMs required for demand-pressure");
                }
                yield new ScalingConfig.DemandPressureConfig(
                        u.exhaustionThreshold(), u.latencyThresholdMs(), cooldown, scaleInCooldown);
            }
            case "proactive" -> {
                if (u.targetActive() == null) {
                    throw new IllegalArgumentException("targetActive required for proactive scaling");
                }
                yield new ScalingConfig.ProactiveConfig(u.targetActive(), cooldown, scaleInCooldown);
            }
            case "none" -> ScalingConfig.NoScalingConfig.INSTANCE;
            default -> new ScalingConfig.CustomScalingConfig(u.scalingType(), cooldown, scaleInCooldown);
        };
    }

    Duration parseDuration(String s) {
        try {
            if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid duration: " + s);
        }
    }

    private boolean hasBudgetFields(PoolUpdateRequest r) {
        return r.costLimit() != null || r.tokenLimit() != null || r.window() != null
               || r.enforcement() != null || r.reportInterval() != null || r.noReportTimeout() != null;
    }

    io.casehub.claudony.casehub.fleet.BudgetConfig parseBudgetConfig(PoolUpdateRequest r, String poolName) {
        var existing = defRegistry.get(poolName)
                                  .map(d -> d.pool().budget())
                                  .orElse(null);
        Double   costLimit  = r.costLimit() != null ? r.costLimit() : (existing != null ? existing.costLimit() : null);
        Long     tokenLimit = r.tokenLimit() != null ? r.tokenLimit() : (existing != null ? existing.tokenLimit() : null);
        Duration window     = r.window() != null ? parseDuration(r.window()) : (existing != null ? existing.window() : Duration.ofHours(24));
        io.casehub.claudony.casehub.fleet.EnforcementPolicy enforcement;
        if (r.enforcement() != null) {
            try { enforcement = io.casehub.claudony.casehub.fleet.EnforcementPolicy.valueOf(r.enforcement().toUpperCase().replace('-', '_')); }
            catch (IllegalArgumentException e) { throw new BadRequestException("Invalid enforcement: " + r.enforcement()); }
        } else {
            enforcement = existing != null ? existing.enforcement() : io.casehub.claudony.casehub.fleet.EnforcementPolicy.BLOCK_NEW;
        }
        var reportInterval = r.reportInterval() != null
                             ? parseReportInterval(r.reportInterval())
                             : (existing != null ? existing.reportInterval() : new io.casehub.claudony.casehub.fleet.ReportInterval.Turn());
        Duration noReportTimeout = r.noReportTimeout() != null ? parseDuration(r.noReportTimeout())
                                                               : (existing != null ? existing.noReportTimeout() : Duration.ofMinutes(10));
        return new io.casehub.claudony.casehub.fleet.BudgetConfig(costLimit, tokenLimit, window, enforcement, reportInterval, noReportTimeout);
    }

    private io.casehub.claudony.casehub.fleet.ReportInterval parseReportInterval(String value) {
        if (value == null || value.equalsIgnoreCase("turn")) {
            return new io.casehub.claudony.casehub.fleet.ReportInterval.Turn();
        }
        if (value.equalsIgnoreCase("completion")) {
            return new io.casehub.claudony.casehub.fleet.ReportInterval.Completion();
        }
        var matcher = java.util.regex.Pattern.compile("periodic\\((\\d+)\\)").matcher(value);
        if (matcher.matches()) {
            return new io.casehub.claudony.casehub.fleet.ReportInterval.Periodic(Integer.parseInt(matcher.group(1)));
        }
        throw new BadRequestException("Invalid report-interval: " + value);
    }


    PoolDetail.ScalingView buildScalingView(ScalingState state, AgentPoolDefinition def) {
        if (state == null && def == null) return null;
        var config = state != null ? state.config() : (def != null ? def.pool().scaling() : null);
        var type = config != null ? config.type() : "none";
        var lastDecision = state != null && state.lastDecision() != null
                ? new PoolDetail.DecisionView(
                state.lastDecision().direction().name(),
                state.lastDecision().count(),
                state.lastDecision().reason(),
                state.lastDecisionTime() != null ? state.lastDecisionTime().toString() : null)
                : null;
        var cooldown = state != null ? formatDuration(state.cooldownRemaining(Instant.now())) : "0s";
        var configView = buildScalingConfigView(config);
        return new PoolDetail.ScalingView(type, configView, lastDecision, cooldown);
    }

    ScalingConfigView buildScalingConfigView(ScalingConfig config) {
        if (config == null) {return null;}
        return switch (config) {
            case ScalingConfig.TargetTrackingConfig t -> new ScalingConfigView(
                    t.targetFillRatio(), null, null, null, null, null,
                    formatDuration(t.cooldown()), formatDuration(t.scaleInCooldown()));
            case ScalingConfig.StepConfig s -> new ScalingConfigView(
                    null,
                    s.steps().stream().map(st -> new ScalingConfigView.ScalingStepView(st.threshold(), st.adjustment())).toList(),
                    null, null, null, null,
                    formatDuration(s.cooldown()), formatDuration(s.scaleInCooldown()));
            case ScalingConfig.DemandPressureConfig d -> new ScalingConfigView(
                    null, null, d.exhaustionThreshold(), d.latencyThresholdMs(), null, null,
                    formatDuration(d.cooldown()), formatDuration(d.scaleInCooldown()));
            case ScalingConfig.CustomScalingConfig c -> new ScalingConfigView(
                    null, null, null, null, c.beanName(), null,
                    formatDuration(c.cooldown()), formatDuration(c.scaleInCooldown()));
            case ScalingConfig.ProactiveConfig p -> new ScalingConfigView(
                    null, null, null, null, null, p.targetActive(),
                    formatDuration(p.cooldown()), formatDuration(p.scaleInCooldown()));
            case ScalingConfig.NoScalingConfig n -> new ScalingConfigView(
                    null, null, null, null, null, null, "0s", "0s");
        };
    }

    private PoolDetail.DemandView buildDemandView(String name) {
        double acquires = counterValue("claudony.pool.acquires.total", name);
        double evictions = counterValue("claudony.pool.evictions.total", name);
        double exhaustions = counterValue("claudony.pool.exhaustions.total", name);
        return new PoolDetail.DemandView(acquires, evictions, exhaustions);
    }

    private PoolDetail.BudgetView buildBudgetView(String name, AgentPoolDefinition def) {
        if (def == null || def.pool().budget() == null) {return null;}
        var    budget = def.pool().budget();
        var    result = budgetTracker.checkBudget(name, budget);
        String status = budgetTracker.isBudgetExceeded(name) ? "EXCEEDED" : "OK";
        return new PoolDetail.BudgetView(
                result.currentCostUsd(),
                budget.costLimit(),
                result.currentTokens(),
                budget.tokenLimit(),
                formatDuration(result.windowRemaining()),
                budget.enforcement().name(),
                status
        );
    }


    private double counterValue(String meterName, String poolName) {
        var counter = meterRegistry.find(meterName).tag("pool", poolName).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private String formatDuration(Duration d) {
        return d == null || d.isZero() ? "0s" : d.toSeconds() + "s";
    }
}
