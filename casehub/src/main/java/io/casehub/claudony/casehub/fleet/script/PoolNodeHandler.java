package io.casehub.claudony.casehub.fleet.script;

import io.casehub.claudony.casehub.fleet.*;
import org.jboss.logging.Logger;

import java.util.Map;

public class PoolNodeHandler implements FleetNodeHandler {

    private static final Logger LOG = Logger.getLogger(PoolNodeHandler.class);

    private final AgentPoolDefinitionRegistry defRegistry;
    private final AgentPoolManagerRegistry mgrRegistry;
    private final SessionOperations ops;

    public PoolNodeHandler(AgentPoolDefinitionRegistry defRegistry,
                           AgentPoolManagerRegistry mgrRegistry,
                           SessionOperations ops) {
        this.defRegistry = defRegistry;
        this.mgrRegistry = mgrRegistry;
        this.ops = ops;
    }

    @Override
    public String type() { return "pool"; }

    @Override
    @SuppressWarnings("unchecked")
    public NodeResult handle(String nodeName, Map<String, Object> spec) {
        var agentId = (String) spec.get("agentId");
        if (agentId == null || agentId.isBlank()) {
            return NodeResult.failed(nodeName, "pool", "agentId is required in pool spec");
        }

        try {
            var builder = AgentPoolDefinition.builder().agent(agentId);

            var workingDir = (String) spec.get("workingDir");
            if (workingDir != null) builder.workingDir(workingDir);

            var policyStr = (String) spec.get("workingDirPolicy");
            if (policyStr != null) builder.policy(WorkingDirPolicy.valueOf(policyStr));

            var command = (String) spec.get("command");
            if (command != null) builder.command(command);

            var poolBuilder = builder.pool();

            if (spec.get("minActive") instanceof Number n) poolBuilder.minActive(n.intValue());
            if (spec.get("maxActive") instanceof Number n) poolBuilder.maxActive(n.intValue());

            var evictionStr = (String) spec.get("eviction");
            if (evictionStr != null) poolBuilder.eviction(EvictionStrategy.valueOf(evictionStr));

            var scalingMap = (Map<String, Object>) spec.get("scaling");
            if (scalingMap != null) {
                poolBuilder.scaling(new AgentPoolYamlParser().parseScalingFromMap(scalingMap));
            }

            var definition = poolBuilder.build();
            defRegistry.register(definition);

            var sessionManager = new AgentSessionManager(
                    definition.toSessionManagerConfig(), ops,
                    SessionLifecycleListener.NOOP, agentId);
            mgrRegistry.register(agentId, sessionManager);

            LOG.infof("Pool '%s' provisioned (agent=%s, min=%d, max=%d)",
                    nodeName, agentId, definition.pool().minActive(), definition.pool().maxActive());
            return NodeResult.ok(nodeName, "pool", "provisioned agent=" + agentId);
        } catch (Exception e) {
            return NodeResult.failed(nodeName, "pool", e.getMessage());
        }
    }
}
