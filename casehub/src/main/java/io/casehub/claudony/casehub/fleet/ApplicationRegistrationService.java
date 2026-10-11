package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.server.TenantContext;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.api.registry.Relationship;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ApplicationRegistrationService {

    private final RegistryService registryService;
    private final AgentPoolDefinitionRegistry poolRegistry;
    private final TenantContext tenantContext;

    @Inject
    public ApplicationRegistrationService(RegistryService registryService,
                                          AgentPoolDefinitionRegistry poolRegistry,
                                          TenantContext tenantContext) {
        this.registryService = registryService;
        this.poolRegistry = poolRegistry;
        this.tenantContext = tenantContext;
    }

    public ApplicationInfo register(ApplicationSpec spec) {
        var tenancyId = tenantContext.currentTenantId();
        var existing = registryService.resolve(spec.name());
        if (existing.isPresent()) {
            throw new IllegalStateException("Application already registered: " + spec.name());
        }

        var now = Instant.now();
        var appEntry = new RegistryEntry(
                spec.name(), "application", spec.name(), tenancyId,
                Map.of("displayName", spec.displayName(),
                       "description", spec.description() != null ? spec.description() : ""),
                now, now, Duration.ofHours(24), HealthStatus.HEALTHY, null);
        registryService.register(appEntry);

        var poolInfos = new java.util.ArrayList<ApplicationInfo.AppPoolInfo>();
        for (var poolSpec : spec.pools()) {
            var poolId = spec.name() + "/" + poolSpec.agentId();
            var def = AgentPoolDefinition.builder()
                    .agent(poolId)
                    .pool()
                        .minActive(poolSpec.minActive())
                        .maxActive(poolSpec.maxActive())
                    .build();
            poolRegistry.register(def, spec.name());
            registryService.link(new Relationship(spec.name(), poolId, "owns"));
            poolInfos.add(new ApplicationInfo.AppPoolInfo(poolSpec.agentId(),
                    poolSpec.minActive(), poolSpec.maxActive()));
        }

        return new ApplicationInfo(spec.name(), spec.displayName(),
                spec.description(), HealthStatus.HEALTHY, now, poolInfos);
    }

    public void deregister(String appName) {
        var rels = registryService.relationships(appName);
        for (var rel : rels) {
            if ("owns".equals(rel.type()) && rel.sourceId().equals(appName)) {
                registryService.deregister(rel.targetId());
            }
        }
        registryService.deregister(appName);
    }

    public Optional<ApplicationInfo> get(String appName) {
        return registryService.resolve(appName)
                .filter(e -> "application".equals(e.type()))
                .map(this::toApplicationInfo);
    }

    public List<ApplicationInfo> list() {
        var tenancyId = tenantContext.currentTenantId();
        return registryService.discover(new RegistryQuery(tenancyId, "application", null))
                .stream()
                .map(this::toApplicationInfo)
                .toList();
    }

    public void heartbeat(String appName) {
        registryService.heartbeat(appName);
    }

    private ApplicationInfo toApplicationInfo(RegistryEntry entry) {
        var rels = registryService.relationships(entry.id());
        var poolInfos = rels.stream()
                .filter(r -> "owns".equals(r.type()) && r.sourceId().equals(entry.id()))
                .map(r -> registryService.resolve(r.targetId()))
                .flatMap(Optional::stream)
                .map(poolEntry -> {
                    var meta = poolEntry.metadata();
                    return new ApplicationInfo.AppPoolInfo(
                            meta.getOrDefault("agentName", poolEntry.id()),
                            Integer.parseInt(meta.getOrDefault("minActive", "0")),
                            Integer.parseInt(meta.getOrDefault("maxActive", "10")));
                })
                .toList();

        return new ApplicationInfo(
                entry.id(),
                entry.metadata().getOrDefault("displayName", entry.id()),
                entry.metadata().getOrDefault("description", ""),
                entry.health(),
                entry.registeredAt(),
                poolInfos);
    }
}
