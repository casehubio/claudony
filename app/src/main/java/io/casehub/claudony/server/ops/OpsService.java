package io.casehub.claudony.server.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.claudony.casehub.fleet.ApplicationRegistrationService;
import io.casehub.claudony.server.fleet.PeerRegistry;
import io.casehub.claudony.server.fleet.PoolService;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

@ApplicationScoped
public class OpsService {

    private final RegistryService registryService;
    private final PoolService poolService;
    private final PeerRegistry peerRegistry;
    private final ApplicationRegistrationService appService;
    private final QhorusDashboardService dashboard;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Inject
    public OpsService(RegistryService registryService, PoolService poolService,
                      PeerRegistry peerRegistry, ApplicationRegistrationService appService,
                      QhorusDashboardService dashboard) {
        this.registryService = registryService;
        this.poolService = poolService;
        this.peerRegistry = peerRegistry;
        this.appService = appService;
        this.dashboard = dashboard;
    }

    public OpsSnapshot snapshot() {
        var fleetHealth = health();
        var pools = poolService.listPools().stream()
                .map(p -> new PoolOpsView(
                        p.name(), p.scalingType(),
                        p.status().active(), p.status().idle(), p.status().max(),
                        p.status().max() > 0 ? (double) p.status().active() / p.status().max() : 0.0,
                        p.status().health().name()))
                .toList();
        var peers = peerRegistry.getAllPeers().stream()
                .map(p -> new PeerOpsView(
                        p.id(), p.name(), p.url(),
                        p.health().name(), p.circuitState().name(),
                        p.sessionCount(), p.stale()))
                .toList();
        var apps = appService.list().stream()
                .map(a -> new AppOpsView(
                        a.name(), a.displayName(),
                        a.health().name(), a.pools().size()))
                .toList();
        var channels = dashboard.listChannels().stream()
                .map(ch -> new ChannelOpsView(ch.name(), ch.description(), ch.messageCount()))
                .toList();
        var instances = dashboard.listInstances();
        var mesh = new MeshOpsView(channels.size(), instances.size(), channels);
        var entries = registryService.discover(new RegistryQuery("default", null, null));
        var seen = new HashSet<String>();
        var topology = entries.stream()
                .flatMap(e -> registryService.relationships(e.id()).stream())
                .filter(r -> seen.add(r.sourceId() + "→" + r.targetId() + ":" + r.type()))
                .map(r -> new TopologyEdge(r.sourceId(), r.targetId(), r.type()))
                .toList();
        return new OpsSnapshot(fleetHealth, pools, peers, apps, mesh, topology, Instant.now());
    }

    public FleetHealth health() {
        var entries = registryService.discover(new RegistryQuery("default", null, null));
        int healthy = 0, degraded = 0, down = 0, orphaned = 0;
        for (var e : entries) {
            if (e.isOrphaned()) orphaned++;
            switch (e.health()) {
                case HEALTHY -> healthy++;
                case DEGRADED -> degraded++;
                case DOWN -> down++;
            }
        }
        return new FleetHealth(entries.size(), healthy, degraded, down, orphaned);
    }

    String buildSnapshotJson() {
        try {
            return MAPPER.writeValueAsString(snapshot());
        } catch (Exception e) {
            return "{}";
        }
    }
}
