package io.casehub.claudony.casehub.fleet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.yaml.core.step.StepValidator;

import io.casehub.platform.api.model.ModelChain;
import io.casehub.platform.api.model.ModelQuery;
import io.casehub.platform.api.model.ModelTier;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentPoolYamlParser {

    private static final Logger LOG = Logger.getLogger(AgentPoolYamlParser.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public List<AgentPoolDefinition> parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {return Collections.emptyList();}

        Map<String, Object> root;
        try {
            root = YAML_MAPPER.readValue(yaml, new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse agent pool YAML", e);
        }

        if (root == null || !root.containsKey("agent-pools")) {return Collections.emptyList();}

        @SuppressWarnings("unchecked")
        var pools = (Map<String, Map<String, Object>>) root.get("agent-pools");
        if (pools == null) {return Collections.emptyList();}

        @SuppressWarnings("unchecked")
        var budgetDefaults = (Map<String, Object>) root.get("budget-defaults");

        var results = new ArrayList<AgentPoolDefinition>();
        for (var entry : pools.entrySet()) {
            results.add(toDefinition(entry.getKey(), entry.getValue(), budgetDefaults));
        }
        return results;
    }

    public void parseInto(String yaml, AgentPoolDefinitionRegistry registry) {
        for (var definition : parse(yaml)) {
            registry.register(definition);
        }
    }

    @SuppressWarnings("unchecked")
    private AgentPoolDefinition toDefinition(String name, Map<String, Object> config, Map<String, Object> budgetDefaults) {
        if (config == null) {config = Map.of();}

        var flat = AgentPoolSchema.flatten(config);
        normalizeEnumValues(flat);
        var errors = StepValidator.validateStep(name, flat, AgentPoolSchema.DEFINITION);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid agent pool definition '" + name + "': " + String.join("; ", errors));
        }

        var agentBuilder = AgentPoolDefinition.builder().agent(name);

        var workingDir = (String) flat.get("working-dir");
        if (workingDir != null) {agentBuilder.workingDir(workingDir);}

        var policy = (String) flat.get("policy");
        if (policy != null) {
            agentBuilder.policy(WorkingDirPolicy.valueOf(policy));
        }

        var command = (String) flat.get("command");
        if (command != null) {agentBuilder.command(command);}

        @SuppressWarnings("unchecked")
        var modelChainList = (List<Object>) config.get("model-chain");
        if (modelChainList != null) {
            var chainResult = parseModelChain(modelChainList);
            agentBuilder.modelChain(chainResult.chain());
            agentBuilder.entryCommands(chainResult.entryCommands());
            agentBuilder.gracePeriods(chainResult.gracePeriods());
        }

        var poolBuilder = agentBuilder.pool();

        var minActive = flat.get("pool.min-active");
        if (minActive instanceof Number n) {poolBuilder.minActive(n.intValue());}

        var maxActive = flat.get("pool.max-active");
        if (maxActive instanceof Number n) {poolBuilder.maxActive(n.intValue());}

        var eviction = (String) flat.get("pool.eviction");
        if (eviction != null) {
            poolBuilder.eviction(EvictionStrategy.valueOf(eviction));
        }

        var scalingMap = (Map<String, Object>) (config.containsKey("pool") && config.get("pool") instanceof Map<?, ?> poolMap
                                                ? ((Map<String, Object>) poolMap).get("scaling") : null);
        poolBuilder.scaling(parseScaling(scalingMap));

        var budgetMap = (Map<String, Object>) (config.containsKey("pool") && config.get("pool") instanceof Map<?, ?> poolMap
                                               ? ((Map<String, Object>) poolMap).get("budget") : null);
        poolBuilder.budget(parseBudget(budgetMap, budgetDefaults));

        return poolBuilder.build();
    }

    record ModelChainParseResult(ModelChain chain, Map<String, String> entryCommands, Map<String, Duration> gracePeriods) {}

    @SuppressWarnings("unchecked")
    ModelChainParseResult parseModelChain(List<Object> chainList) {
        var entries = new ArrayList<ModelChain.ModelChainEntry>();
        var commands = new LinkedHashMap<String, String>();
        var gracePeriods = new LinkedHashMap<String, Duration>();

        for (Object item : chainList) {
            if (item instanceof String s) {
                entries.add(new ModelChain.ModelChainEntry.Named(s));
            } else if (item instanceof Map<?, ?> rawMap) {
                var m = (Map<String, Object>) rawMap;
                if (m.containsKey("model")) {
                    var name = (String) m.get("model");
                    entries.add(new ModelChain.ModelChainEntry.Named(name));
                    if (m.containsKey("command")) {
                        commands.put(name, (String) m.get("command"));
                    }
                    if (m.containsKey("grace-period")) {
                        gracePeriods.put(name, parseDuration((String) m.get("grace-period")));
                    }
                } else if (m.containsKey("tier")) {
                    var builder = ModelQuery.builder()
                            .tier(ModelTier.valueOf(((String) m.get("tier")).toUpperCase()));
                    if (m.containsKey("vendor")) builder.vendor((String) m.get("vendor"));
                    if (m.containsKey("max-cost-tier")) {
                        builder.maxCostTier(io.casehub.platform.api.model.CostTier.valueOf(
                                ((String) m.get("max-cost-tier")).toUpperCase()));
                    }
                    if (m.containsKey("min-context-window") && m.get("min-context-window") instanceof Number n) {
                        builder.minContextWindow(n.intValue());
                    }
                    if (m.containsKey("capabilities") && m.get("capabilities") instanceof List<?> capList) {
                        builder.requiredCapabilities(new java.util.HashSet<>(capList.stream()
                                .map(Object::toString).toList()));
                    }
                    entries.add(new ModelChain.ModelChainEntry.Queried(builder.build()));
                }
            }
        }

        var seen = new HashSet<String>();
        for (var entry : entries) {
            if (entry instanceof ModelChain.ModelChainEntry.Named n && !seen.add(n.modelRef())) {
                LOG.warnf("Duplicate model '%s' in chain — same model tried twice is likely misconfiguration", n.modelRef());
            }
        }

        return new ModelChainParseResult(ModelChain.of(entries), Map.copyOf(commands), Map.copyOf(gracePeriods));
    }

    private static void normalizeEnumValues(Map<String, Object> flat) {
        for (var key : List.of("policy", "pool.eviction")) {
            var value = flat.get(key);
            if (value instanceof String s) {
                flat.put(key, s.toUpperCase().replace('-', '_'));
            }
        }
    }


    public ScalingConfig parseScalingFromMap(Map<String, Object> scalingMap) {
        return parseScaling(scalingMap);
    }

    @SuppressWarnings("unchecked")
    ScalingConfig parseScaling(Map<String, Object> scalingMap) {
        if (scalingMap == null || scalingMap.isEmpty()) {
            return ScalingConfig.NoScalingConfig.INSTANCE;
        }

        var type = (String) scalingMap.get("type");
        if (type == null) {
            throw new IllegalArgumentException("scaling config requires a 'type' field");
        }

        Duration cooldown = parseDuration((String) scalingMap.get("cooldown"));
        Duration scaleInCooldown = parseDuration((String) scalingMap.get("scale-in-cooldown"));

        return switch (type) {
            case "target-tracking" -> {
                var target = scalingMap.get("target");
                if (target == null) {
                    throw new IllegalArgumentException("target-tracking scaling requires a 'target' field");
                }
                double targetValue = target instanceof Number n ? n.doubleValue() : Double.parseDouble(target.toString());
                yield new ScalingConfig.TargetTrackingConfig(targetValue, cooldown, scaleInCooldown);
            }
            case "step" -> {
                var stepsList = (List<Map<String, Object>>) scalingMap.get("steps");
                if (stepsList == null || stepsList.isEmpty()) {
                    throw new IllegalArgumentException("step scaling requires a non-empty 'steps' list");
                }
                var steps = stepsList.stream().map(s -> {
                    var threshold = s.get("threshold");
                    var adjustment = s.get("adjustment");
                    return new ScalingStep(
                        threshold instanceof Number n ? n.doubleValue() : Double.parseDouble(threshold.toString()),
                        adjustment instanceof Number n ? n.intValue() : Integer.parseInt(adjustment.toString())
                    );
                }).toList();
                yield new ScalingConfig.StepConfig(steps, cooldown, scaleInCooldown);
            }
            case "demand-pressure" -> {
                var exhaustionThreshold = scalingMap.get("exhaustion-threshold");
                if (exhaustionThreshold == null) {
                    throw new IllegalArgumentException("demand-pressure scaling requires 'exhaustion-threshold'");
                }
                var latencyThresholdMs = scalingMap.get("latency-threshold-ms");
                if (latencyThresholdMs == null) {
                    throw new IllegalArgumentException("demand-pressure scaling requires 'latency-threshold-ms'");
                }
                yield new ScalingConfig.DemandPressureConfig(
                    exhaustionThreshold instanceof Number n ? n.intValue() : Integer.parseInt(exhaustionThreshold.toString()),
                    latencyThresholdMs instanceof Number n ? n.longValue() : Long.parseLong(latencyThresholdMs.toString()),
                    cooldown, scaleInCooldown);
            }
            case "proactive" -> {
                var targetActive = scalingMap.get("target-active");
                if (targetActive == null) {
                    throw new IllegalArgumentException("proactive scaling requires a 'target-active' field");
                }
                int target = targetActive instanceof Number n ? n.intValue() : Integer.parseInt(targetActive.toString());
                yield new ScalingConfig.ProactiveConfig(target, cooldown, scaleInCooldown);
            }
            case "none" -> ScalingConfig.NoScalingConfig.INSTANCE;
            default -> new ScalingConfig.CustomScalingConfig(type, cooldown, scaleInCooldown);
        };
    }

    @SuppressWarnings("unchecked")
    private BudgetConfig parseBudget(Map<String, Object> budgetMap, Map<String, Object> defaults) {
        if (budgetMap == null && defaults == null) {return null;}
        if (budgetMap == null) {return null;}

        var merged = new java.util.HashMap<String, Object>();
        if (defaults != null) {merged.putAll(defaults);}
        merged.putAll(budgetMap);

        Double costLimit = null;
        var    cl        = merged.get("cost-limit");
        if (cl instanceof Number n) {costLimit = n.doubleValue();}

        Long tokenLimit = null;
        var  tl         = merged.get("token-limit");
        if (tl instanceof Number n) {tokenLimit = n.longValue();}

        var      windowStr = (String) merged.get("window");
        Duration window    = windowStr != null ? parseDuration(windowStr) : Duration.ofHours(24);

        var enforcementStr = (String) merged.get("enforcement");
        EnforcementPolicy enforcement = enforcementStr != null
                                        ? EnforcementPolicy.valueOf(enforcementStr.toUpperCase().replace('-', '_'))
                                        : EnforcementPolicy.BLOCK_NEW;

        var            intervalStr    = (String) merged.get("report-interval");
        ReportInterval reportInterval = parseReportInterval(intervalStr);

        var      timeoutStr      = (String) merged.get("no-report-timeout");
        Duration noReportTimeout = timeoutStr != null ? parseDuration(timeoutStr) : Duration.ofMinutes(10);

        return new BudgetConfig(costLimit, tokenLimit, window, enforcement, reportInterval, noReportTimeout);
    }

    private static ReportInterval parseReportInterval(String value) {
        if (value == null || value.equalsIgnoreCase("turn")) {return new ReportInterval.Turn();}
        if (value.equalsIgnoreCase("completion")) {return new ReportInterval.Completion();}
        var matcher = java.util.regex.Pattern.compile("periodic\\((\\d+)\\)").matcher(value);
        if (matcher.matches()) {return new ReportInterval.Periodic(Integer.parseInt(matcher.group(1)));}
        throw new IllegalArgumentException("Unknown report-interval: " + value + ". Expected: turn, completion, or periodic(N)");
    }


    private static Duration parseDuration(String value) {
        if (value == null) {return null;}
        value = value.trim();
        if (value.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        if (value.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        if (value.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        return Duration.ofSeconds(Long.parseLong(value));
    }

}
