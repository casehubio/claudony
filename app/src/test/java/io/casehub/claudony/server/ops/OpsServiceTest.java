package io.casehub.claudony.server.ops;

import io.casehub.claudony.casehub.fleet.AgentPoolHealth;
import io.casehub.claudony.casehub.fleet.AgentPoolStatus;
import io.casehub.claudony.casehub.fleet.ApplicationInfo;
import io.casehub.claudony.casehub.fleet.ApplicationRegistrationService;
import io.casehub.claudony.server.fleet.CircuitState;
import io.casehub.claudony.server.fleet.DiscoverySource;
import io.casehub.claudony.server.fleet.PeerHealth;
import io.casehub.claudony.server.fleet.PeerRecord;
import io.casehub.claudony.server.fleet.PeerRegistry;
import io.casehub.claudony.server.fleet.PoolService;
import io.casehub.claudony.server.fleet.PoolSummary;
import io.casehub.claudony.server.fleet.TerminalMode;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.api.registry.Relationship;
import io.casehub.qhorus.api.channel.ChannelDetail;
import io.casehub.qhorus.api.instance.InstanceInfo;
import io.casehub.qhorus.runtime.dashboard.QhorusDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpsServiceTest {

    private OpsService service;
    private RegistryService registryService;
    private PoolService poolService;
    private PeerRegistry peerRegistry;
    private ApplicationRegistrationService appService;
    private QhorusDashboardService dashboard;

    @BeforeEach
    void setUp() {
        registryService = mock(RegistryService.class);
        poolService = mock(PoolService.class);
        peerRegistry = mock(PeerRegistry.class);
        appService = mock(ApplicationRegistrationService.class);
        dashboard = mock(QhorusDashboardService.class);
        service = new OpsService(registryService, poolService, peerRegistry, appService, dashboard);
    }

    @Test
    void snapshotAssemblesAllSources() {
        when(registryService.discover(any())).thenReturn(List.of(
                new RegistryEntry("pool-default", "pool", "claudony", "default",
                        Map.of(), Instant.now(), Instant.now(), Duration.ofMinutes(5), HealthStatus.HEALTHY, null)
        ));
        when(registryService.relationships("pool-default")).thenReturn(List.of(
                new Relationship("app-scaffold", "pool-default", "consumes")
        ));
        when(poolService.listPools()).thenReturn(List.of(
                new PoolSummary("default", new AgentPoolStatus(0, 5, 2, 3, 5, AgentPoolHealth.HEALTHY), "target-tracking")
        ));
        when(peerRegistry.getAllPeers()).thenReturn(List.of(
                new PeerRecord("peer-1", "http://mac-mini:7777", "Mac Mini",
                        DiscoverySource.CONFIG, TerminalMode.PROXY, PeerHealth.UP, CircuitState.CLOSED,
                        Instant.now(), false, 3)
        ));
        when(appService.list()).thenReturn(List.of(
                new ApplicationInfo("scaffold", "default", "Scaffold", "Build tool", null, Map.of(), HealthStatus.HEALTHY, Instant.now(), List.of())
        ));
        when(dashboard.listChannels()).thenReturn(List.of(
                new ChannelDetail(UUID.randomUUID(), "team/general", "General chat", null,
                        null, 42, null, false, null, null, null, null, null, null, null, null, null, null)
        ));
        when(dashboard.listInstances()).thenReturn(List.of(
                new InstanceInfo("inst-1", "worker", "ONLINE", List.of("code"), Instant.now().toString(), false)
        ));

        var snapshot = service.snapshot();

        assertNotNull(snapshot);
        assertNotNull(snapshot.timestamp());
        assertEquals(1, snapshot.pools().size());
        assertEquals("default", snapshot.pools().get(0).name());
        assertEquals(0.4, snapshot.pools().get(0).fillRatio(), 0.01);
        assertEquals("HEALTHY", snapshot.pools().get(0).healthStatus());
        assertEquals(1, snapshot.peers().size());
        assertEquals("Mac Mini", snapshot.peers().get(0).name());
        assertEquals("UP", snapshot.peers().get(0).health());
        assertEquals(1, snapshot.apps().size());
        assertEquals("scaffold", snapshot.apps().get(0).name());
        assertEquals(1, snapshot.mesh().totalChannels());
        assertEquals(1, snapshot.mesh().totalInstances());
        assertEquals(42, snapshot.mesh().channels().get(0).messageCount());
        assertEquals("General chat", snapshot.mesh().channels().get(0).purpose());
        assertEquals(1, snapshot.topology().size());
        assertEquals("consumes", snapshot.topology().get(0).type());
    }

    @Test
    void healthCountsFromRegistryEntries() {
        when(registryService.discover(any())).thenReturn(List.of(
                new RegistryEntry("a", "pool", "ns", "default", Map.of(), Instant.now(), Instant.now(), Duration.ofMinutes(5), HealthStatus.HEALTHY, null),
                new RegistryEntry("b", "peer", "ns", "default", Map.of(), Instant.now(), Instant.now(), Duration.ofMinutes(5), HealthStatus.DEGRADED, null),
                new RegistryEntry("c", "app", "ns", "default", Map.of(), Instant.now(), Instant.now(), Duration.ofMinutes(5), HealthStatus.DOWN, null),
                new RegistryEntry("d", "pool", "ns", "default", Map.of(), Instant.now(), Instant.now(), Duration.ofMinutes(5), HealthStatus.HEALTHY, Instant.now())
        ));

        var health = service.health();

        assertEquals(4, health.totalEntities());
        assertEquals(2, health.healthy());
        assertEquals(1, health.degraded());
        assertEquals(1, health.down());
        assertEquals(1, health.orphaned());
    }

    @Test
    void fillRatioZeroWhenMaxIsZero() {
        when(registryService.discover(any())).thenReturn(List.of());
        when(poolService.listPools()).thenReturn(List.of(
                new PoolSummary("empty", new AgentPoolStatus(0, 0, 0, 0, 0, AgentPoolHealth.HEALTHY), "none")
        ));
        when(peerRegistry.getAllPeers()).thenReturn(List.of());
        when(appService.list()).thenReturn(List.of());
        when(dashboard.listChannels()).thenReturn(List.of());
        when(dashboard.listInstances()).thenReturn(List.of());

        var snapshot = service.snapshot();
        assertEquals(0.0, snapshot.pools().get(0).fillRatio());
    }

    @Test
    void buildSnapshotJsonReturnsValidJson() {
        when(registryService.discover(any())).thenReturn(List.of());
        when(poolService.listPools()).thenReturn(List.of());
        when(peerRegistry.getAllPeers()).thenReturn(List.of());
        when(appService.list()).thenReturn(List.of());
        when(dashboard.listChannels()).thenReturn(List.of());
        when(dashboard.listInstances()).thenReturn(List.of());

        var json = service.buildSnapshotJson();
        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("\"health\""));
    }
}
