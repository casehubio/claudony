package io.casehub.claudony.casehub.fleet;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentMatch;
import io.casehub.eidos.api.AgentQuery;
import io.casehub.eidos.api.AgentRegistry;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
@IfBuildProperty(name = "claudony.eidos.pool-bridge.enabled", stringValue = "true")
public class EidosPoolBridge {

    private static final Logger LOG = Logger.getLogger(EidosPoolBridge.class);

    private final AgentRegistry agentRegistry;
    private final AgentPoolDefinitionRegistry poolRegistry;
    private final EidosPoolConverter converter;
    private final EidosPoolBridgeConfig config;
    private final String tenancyId;
    private final java.util.Map<String, AgentPoolDefinition> lastKnownDefinitions = new java.util.concurrent.ConcurrentHashMap<>();


    @Inject
    public EidosPoolBridge(
            AgentRegistry agentRegistry,
            AgentPoolDefinitionRegistry poolRegistry,
            jakarta.enterprise.inject.Instance<io.casehub.platform.api.model.ModelRegistry> modelRegistry,
            @ConfigProperty(name = "claudony.eidos.pool-bridge.default-min-active", defaultValue = "0") int defaultMinActive,
            @ConfigProperty(name = "claudony.eidos.pool-bridge.default-max-active", defaultValue = "5") int defaultMaxActive,
            @ConfigProperty(name = "claudony.eidos.pool-bridge.default-command", defaultValue = "claude") String defaultCommand,
            @ConfigProperty(name = "claudony.eidos.pool-bridge.tenancy-id", defaultValue = "default") String tenancyId) {
        this.agentRegistry = agentRegistry;
        this.poolRegistry = poolRegistry;
        this.converter = modelRegistry.isResolvable() ? new EidosPoolConverter(modelRegistry.get()) : new EidosPoolConverter();
        this.config = new EidosPoolBridgeConfig(defaultMinActive, defaultMaxActive, defaultCommand);
        this.tenancyId = tenancyId;
    }

    EidosPoolBridge(AgentRegistry agentRegistry,
                    AgentPoolDefinitionRegistry poolRegistry,
                    EidosPoolBridgeConfig config,
                    String tenancyId) {
        this.agentRegistry = agentRegistry;
        this.poolRegistry = poolRegistry;
        this.converter = new EidosPoolConverter();
        this.config = config;
        this.tenancyId = tenancyId;
    }

    void onStartup(@Observes StartupEvent event) {
        onStartup();
    }

    void onStartup() {
        LOG.info("Eidos pool bridge: starting");
        try {
            List<AgentMatch> matches = agentRegistry.find(AgentQuery.all(tenancyId));
            int registered = 0;
            for (AgentMatch match : matches) {
                if (registerIfClaudony(match.descriptor())) {
                    registered++;
                }
            }
            LOG.infof("Eidos pool bridge: registered %d pool(s) from %d agent(s)", registered, matches.size());
        } catch (Exception e) {
            LOG.error("Eidos pool bridge: startup failed, will retry on next reconciliation", e);
        }
    }

    @Scheduled(every = "${claudony.eidos.pool-bridge.reconcile-interval:30s}")
    void reconcile() {
        try {
            java.util.List<AgentMatch> matches       = agentRegistry.find(AgentQuery.all(tenancyId));
            java.util.Set<String>      existingPools = poolRegistry.names();
            java.util.Set<String>      currentIds    = new java.util.HashSet<>();
            int                        added         = 0, updated = 0;

            for (AgentMatch match : matches) {
                AgentDescriptor descriptor = match.descriptor();
                if (EidosPoolConverter.findClaudonyProviderConfig(descriptor).isEmpty()) {continue;}

                currentIds.add(descriptor.agentId());
                var newDef   = converter.toPoolDefinition(descriptor, config);
                var existing = lastKnownDefinitions.get(descriptor.agentId());

                if (existing == null && !existingPools.contains(descriptor.agentId())) {
                    if (registerIfClaudony(descriptor)) {
                        added++;
                    }
                } else if (existing == null) {
                    lastKnownDefinitions.put(descriptor.agentId(), newDef);
                } else if (!newDef.agent().equals(existing.agent()) || !newDef.pool().equals(existing.pool())) {
                    if (newDef.pool().minActive() != existing.pool().minActive()
                        || newDef.pool().maxActive() != existing.pool().maxActive()) {
                        poolRegistry.updateCapacity(descriptor.agentId(), newDef.pool().minActive(), newDef.pool().maxActive());
                    }
                    if (!newDef.agent().equals(existing.agent())) {
                        poolRegistry.updateAgentConfig(descriptor.agentId(), newDef.agent());
                    }
                    lastKnownDefinitions.put(descriptor.agentId(), newDef);
                    updated++;
                }
            }

            for (String knownId : lastKnownDefinitions.keySet()) {
                if (!currentIds.contains(knownId)) {
                    LOG.warnf("Eidos pool bridge: descriptor '%s' no longer in registry (pool retained)", knownId);
                }
            }

            if (added > 0 || updated > 0) {
                LOG.infof("Eidos pool bridge: reconciliation — added %d, updated %d pool(s)", added, updated);
            }
        } catch (Exception e) {
            LOG.warn("Eidos pool bridge: reconciliation failed", e);
        }
    }

    private boolean registerIfClaudony(AgentDescriptor descriptor) {
        if (EidosPoolConverter.findClaudonyProviderConfig(descriptor).isEmpty()) {
            return false;
        }
        try {
            var definition = converter.toPoolDefinition(descriptor, config);
            poolRegistry.register(definition, "eidos");
            lastKnownDefinitions.put(descriptor.agentId(), definition);
            return true;
        } catch (Exception e) {
            LOG.warnf("Eidos pool bridge: failed to register pool for agent '%s': %s",
                      descriptor.agentId(), e.getMessage());
            return false;
        }
    }
}
