package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.server.TenantContext;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.api.registry.Relationship;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

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
        var existing  = registryService.resolve(spec.name());
        if (existing.isPresent()) {
            throw new IllegalStateException("Application already registered: " + spec.name());
        }

        var now      = Instant.now();
        var metadata = buildMetadata(spec);
        var appEntry = new RegistryEntry(
                spec.name(), "application", spec.namespace(), tenancyId,
                Map.copyOf(metadata),
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
            poolRegistry.register(def, spec.namespace());
            registryService.link(new Relationship(spec.name(), poolId, "owns"));
            poolInfos.add(new ApplicationInfo.AppPoolInfo(poolSpec.agentId(),
                                                          poolSpec.minActive(), poolSpec.maxActive()));
        }

        return new ApplicationInfo(spec.name(), spec.namespace(), spec.displayName(),
                                   spec.description(), spec.image(), spec.config(),
                                   HealthStatus.HEALTHY, now, poolInfos);
    }


    public ApplicationInfo update(String name, ApplicationSpec spec) {
        if (!name.equals(spec.name())) {
            throw new IllegalArgumentException("spec name must match path name");
        }

        var existing = registryService.resolve(name)
                                      .filter(e -> "application".equals(e.type()))
                                      .orElseThrow(() -> new WebApplicationException(Response.Status.NOT_FOUND));

        var originalRegisteredAt  = existing.registeredAt();
        var originalLastHeartbeat = existing.lastHeartbeat();
        var originalHealth        = existing.health();

        var oldPoolIds = registryService.relationships(name).stream()
                                        .filter(r -> "owns".equals(r.type()) && r.sourceId().equals(name))
                                        .map(Relationship::targetId)
                                        .collect(java.util.stream.Collectors.toSet());

        registryService.deregister(name);

        var tenancyId = tenantContext.currentTenantId();
        var metadata  = buildMetadata(spec);
        var appEntry = new RegistryEntry(
                spec.name(), "application", spec.namespace(), tenancyId,
                Map.copyOf(metadata),
                originalRegisteredAt, originalLastHeartbeat,
                Duration.ofHours(24), originalHealth, null);
        registryService.register(appEntry);

        var newPoolIds = new java.util.HashSet<String>();
        var poolInfos  = new java.util.ArrayList<ApplicationInfo.AppPoolInfo>();
        for (var poolSpec : spec.pools()) {
            var poolId = spec.name() + "/" + poolSpec.agentId();
            newPoolIds.add(poolId);

            if (oldPoolIds.contains(poolId)) {
                poolRegistry.updateCapacity(poolId, poolSpec.minActive(), poolSpec.maxActive());
            } else {
                var def = AgentPoolDefinition.builder()
                                             .agent(poolId)
                                             .pool()
                                             .minActive(poolSpec.minActive())
                                             .maxActive(poolSpec.maxActive())
                                             .build();
                poolRegistry.register(def, spec.namespace());
            }
            registryService.link(new Relationship(spec.name(), poolId, "owns"));
            poolInfos.add(new ApplicationInfo.AppPoolInfo(poolSpec.agentId(),
                                                          poolSpec.minActive(), poolSpec.maxActive()));
        }

        for (var oldId : oldPoolIds) {
            if (!newPoolIds.contains(oldId)) {
                poolRegistry.deregister(oldId);
            }
        }

        return new ApplicationInfo(spec.name(), spec.namespace(), spec.displayName(),
                                   spec.description(), spec.image(), spec.config(),
                                   originalHealth, originalRegisteredAt, poolInfos);
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

    public List<ApplicationInfo> list(String namespace) {
        var tenancyId = tenantContext.currentTenantId();
        return registryService.discover(new RegistryQuery(tenancyId, "application", namespace))
                              .stream()
                              .map(this::toApplicationInfo)
                              .toList();
    }


    public void heartbeat(String appName) {
        registryService.heartbeat(appName);
    }

    public void suspend(String name) {
        var existing = registryService.resolve(name)
                                      .filter(e -> "application".equals(e.type()))
                                      .orElseThrow(() -> new WebApplicationException(Response.Status.NOT_FOUND));

        if (existing.health() == HealthStatus.SUSPENDED) {
            return;
        }

        var rels = registryService.relationships(name);
        registryService.deregister(name);
        var updated = new RegistryEntry(
                existing.id(), existing.type(), existing.namespace(), existing.tenancyId(),
                existing.metadata(),
                existing.registeredAt(), existing.lastHeartbeat(),
                existing.ttl(), HealthStatus.SUSPENDED, existing.orphanedAt());
        registryService.register(updated);

        for (var rel : rels) {
            if ("owns".equals(rel.type()) && rel.sourceId().equals(name)) {
                registryService.link(rel);
            }
        }
    }

    public void resume(String name) {
        var existing = registryService.resolve(name)
                                      .filter(e -> "application".equals(e.type()))
                                      .orElseThrow(() -> new WebApplicationException(Response.Status.NOT_FOUND));

        if (existing.health() != HealthStatus.SUSPENDED) {
            return;
        }

        var rels = registryService.relationships(name);
        registryService.deregister(name);
        var updated = new RegistryEntry(
                existing.id(), existing.type(), existing.namespace(), existing.tenancyId(),
                existing.metadata(),
                existing.registeredAt(), existing.lastHeartbeat(),
                existing.ttl(), HealthStatus.HEALTHY, existing.orphanedAt());
        registryService.register(updated);

        for (var rel : rels) {
            if ("owns".equals(rel.type()) && rel.sourceId().equals(name)) {
                registryService.link(rel);
            }
        }
    }


    private Map<String, String> buildMetadata(ApplicationSpec spec) {
        var metadata = new java.util.HashMap<String, String>();
        metadata.put("displayName", spec.displayName());
        metadata.put("description", spec.description() != null ? spec.description() : "");
        if (spec.image() != null) {
            metadata.put("image", spec.image());
        }
        for (var entry : spec.config().entrySet()) {
            metadata.put("config." + entry.getKey(), entry.getValue());
        }
        return metadata;
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

        var image = entry.metadata().get("image");
        var config = entry.metadata().entrySet().stream()
                          .filter(e -> e.getKey().startsWith("config."))
                          .collect(java.util.stream.Collectors.toMap(
                                  e -> e.getKey().substring("config.".length()),
                                  Map.Entry::getValue));

        return new ApplicationInfo(
                entry.id(),
                entry.namespace(),
                entry.metadata().getOrDefault("displayName", entry.id()),
                entry.metadata().getOrDefault("description", ""),
                image,
                config,
                entry.health(),
                entry.registeredAt(),
                poolInfos);
    }
}
