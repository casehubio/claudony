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
import java.util.Set;

@ApplicationScoped
@IfBuildProperty(name = "claudony.eidos.pool-bridge.enabled", stringValue = "true")
public class EidosPoolBridge {

    private static final Logger LOG = Logger.getLogger(EidosPoolBridge.class);

    private final AgentRegistry agentRegistry;
    private final AgentPoolDefinitionRegistry poolRegistry;
    private final EidosPoolBridgeConfig config;
    private final String tenancyId;

    @Inject
    public EidosPoolBridge(
            AgentRegistry agentRegistry,
            AgentPoolDefinitionRegistry poolRegistry,
            @ConfigProperty(name = "claudony.eidos.pool-bridge.default-min-active", defaultValue = "0") int defaultMinActive,
            @ConfigProperty(name = "claudony.eidos.pool-bridge.default-max-active", defaultValue = "5") int defaultMaxActive,
            @ConfigProperty(name = "claudony.eidos.pool-bridge.default-command", defaultValue = "claude") String defaultCommand,
            @ConfigProperty(name = "claudony.eidos.pool-bridge.tenancy-id", defaultValue = "default") String tenancyId) {
        this.agentRegistry = agentRegistry;
        this.poolRegistry = poolRegistry;
        this.config = new EidosPoolBridgeConfig(defaultMinActive, defaultMaxActive, defaultCommand);
        this.tenancyId = tenancyId;
    }

    EidosPoolBridge(AgentRegistry agentRegistry,
                    AgentPoolDefinitionRegistry poolRegistry,
                    EidosPoolBridgeConfig config,
                    String tenancyId) {
        this.agentRegistry = agentRegistry;
        this.poolRegistry = poolRegistry;
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
            List<AgentMatch> matches = agentRegistry.find(AgentQuery.all(tenancyId));
            Set<String> existingPools = poolRegistry.names();
            int added = 0;
            for (AgentMatch match : matches) {
                AgentDescriptor descriptor = match.descriptor();
                if (EidosPoolConverter.findClaudonyProviderConfig(descriptor).isEmpty()) {
                    continue;
                }
                if (!existingPools.contains(descriptor.agentId())) {
                    if (registerIfClaudony(descriptor)) {
                        added++;
                    }
                }
            }
            if (added > 0) {
                LOG.infof("Eidos pool bridge: reconciliation added %d new pool(s)", added);
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
            var definition = EidosPoolConverter.toPoolDefinition(descriptor, config);
            poolRegistry.register(definition, "eidos");
            return true;
        } catch (Exception e) {
            LOG.warnf("Eidos pool bridge: failed to register pool for agent '%s': %s",
                    descriptor.agentId(), e.getMessage());
            return false;
        }
    }
}
