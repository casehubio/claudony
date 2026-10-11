package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.server.TenantContext;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationRegistrationServiceTest {

    private RegistryService registryService;
    private AgentPoolDefinitionRegistry poolRegistry;
    private ApplicationRegistrationService service;
    private final TenantContext tenantContext = () -> "default";

    @BeforeEach
    void setUp() {
        registryService = new InMemoryRegistryService(event -> {});
        poolRegistry = new AgentPoolDefinitionRegistry(registryService);
        service = new ApplicationRegistrationService(registryService, poolRegistry, tenantContext);
    }

    @Test
    void register_createsAppEntryWithCorrectTypeAndNamespace() {
        var spec = new ApplicationSpec("scaffold", "Scaffold", "Dev platform", List.of());

        service.register(spec);

        var entries = registryService.discover(new RegistryQuery("default", "application", "scaffold"));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).id()).isEqualTo("scaffold");
        assertThat(entries.get(0).type()).isEqualTo("application");
        assertThat(entries.get(0).namespace()).isEqualTo("scaffold");
        assertThat(entries.get(0).metadata()).containsEntry("displayName", "Scaffold");
    }

    @Test
    void register_createsPoolsWithAppNamespace() {
        var spec = new ApplicationSpec("scaffold", "Scaffold", "Dev platform",
                List.of(new ApplicationSpec.PoolSpec("code-review", 1, 5)));

        service.register(spec);

        var pools = registryService.discover(new RegistryQuery("default", "pool", "scaffold"));
        assertThat(pools).hasSize(1);
        assertThat(pools.get(0).id()).isEqualTo("scaffold/code-review");
        assertThat(pools.get(0).namespace()).isEqualTo("scaffold");
    }

    @Test
    void register_linksAppToPoolsWithOwnsRelationship() {
        var spec = new ApplicationSpec("scaffold", "Scaffold", "Dev",
                List.of(new ApplicationSpec.PoolSpec("review", 0, 3)));

        service.register(spec);

        var rels = registryService.relationships("scaffold");
        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).sourceId()).isEqualTo("scaffold");
        assertThat(rels.get(0).targetId()).isEqualTo("scaffold/review");
        assertThat(rels.get(0).type()).isEqualTo("owns");
    }

    @Test
    void register_poolIdIncludesAppPrefix() {
        var spec = new ApplicationSpec("trellis", "Trellis", "IoT",
                List.of(new ApplicationSpec.PoolSpec("monitor", 0, 2)));

        service.register(spec);

        assertThat(poolRegistry.get("trellis/monitor")).isPresent();
    }

    @Test
    void register_duplicateNameThrows() {
        var spec = new ApplicationSpec("myapp", "My App", "test", List.of());
        service.register(spec);

        assertThatThrownBy(() -> service.register(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myapp");
    }

    @Test
    void deregister_removesAppAndPools() {
        var spec = new ApplicationSpec("scaffold", "Scaffold", "Dev",
                List.of(new ApplicationSpec.PoolSpec("review", 0, 3)));
        service.register(spec);

        service.deregister("scaffold");

        assertThat(registryService.resolve("scaffold")).isEmpty();
        assertThat(registryService.resolve("scaffold/review")).isEmpty();
    }

    @Test
    void deregister_unknownAppNoOp() {
        service.deregister("nonexistent");
    }

    @Test
    void get_returnsAppWithPools() {
        var spec = new ApplicationSpec("scaffold", "Scaffold", "Dev",
                List.of(new ApplicationSpec.PoolSpec("review", 1, 5)));
        service.register(spec);

        var result = service.get("scaffold");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("scaffold");
        assertThat(result.get().displayName()).isEqualTo("Scaffold");
        assertThat(result.get().pools()).hasSize(1);
        assertThat(result.get().pools().get(0).agentId()).isEqualTo("scaffold/review");
    }

    @Test
    void list_returnsAllApps() {
        service.register(new ApplicationSpec("app1", "App 1", "first", List.of()));
        service.register(new ApplicationSpec("app2", "App 2", "second", List.of()));

        var apps = service.list();

        assertThat(apps).hasSize(2);
        assertThat(apps).extracting(ApplicationInfo::name).containsExactlyInAnyOrder("app1", "app2");
    }

    @Test
    void heartbeat_updatesAppEntry() {
        service.register(new ApplicationSpec("myapp", "My App", "test", List.of()));

        service.heartbeat("myapp");

        var entry = registryService.resolve("myapp");
        assertThat(entry).isPresent();
    }

    @Test
    void register_multipleAppsIsolatedByNamespace() {
        service.register(new ApplicationSpec("app-a", "App A", "",
                List.of(new ApplicationSpec.PoolSpec("worker", 0, 5))));
        service.register(new ApplicationSpec("app-b", "App B", "",
                List.of(new ApplicationSpec.PoolSpec("worker", 0, 3))));

        var appAPools = registryService.discover(new RegistryQuery("default", "pool", "app-a"));
        var appBPools = registryService.discover(new RegistryQuery("default", "pool", "app-b"));

        assertThat(appAPools).hasSize(1);
        assertThat(appBPools).hasSize(1);
        assertThat(appAPools.get(0).id()).isEqualTo("app-a/worker");
        assertThat(appBPools.get(0).id()).isEqualTo("app-b/worker");
    }
}
