package io.casehub.claudony.casehub.fleet;

import io.casehub.claudony.server.TenantContext;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        var spec = new ApplicationSpec("scaffold", "default", "Scaffold", "Dev platform", null, null, List.of());

        service.register(spec);

        var entries = registryService.discover(new RegistryQuery("default", "application", "default"));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).id()).isEqualTo("scaffold");
        assertThat(entries.get(0).type()).isEqualTo("application");
        assertThat(entries.get(0).namespace()).isEqualTo("default");
        assertThat(entries.get(0).metadata()).containsEntry("displayName", "Scaffold");
    }

    @Test
    void register_createsPoolsWithAppNamespace() {
        var spec = new ApplicationSpec("scaffold", "default", "Scaffold", "Dev platform", null, null,
                List.of(new ApplicationSpec.PoolSpec("code-review", 1, 5)));

        service.register(spec);

        var pools = registryService.discover(new RegistryQuery("default", "pool", "default"));
        assertThat(pools).hasSize(1);
        assertThat(pools.get(0).id()).isEqualTo("scaffold/code-review");
        assertThat(pools.get(0).namespace()).isEqualTo("default");
    }

    @Test
    void register_linksAppToPoolsWithOwnsRelationship() {
        var spec = new ApplicationSpec("scaffold", "default", "Scaffold", "Dev", null, null,
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
        var spec = new ApplicationSpec("trellis", "default", "Trellis", "IoT", null, null,
                List.of(new ApplicationSpec.PoolSpec("monitor", 0, 2)));

        service.register(spec);

        assertThat(poolRegistry.get("trellis/monitor")).isPresent();
    }

    @Test
    void register_duplicateNameThrows() {
        var spec = new ApplicationSpec("myapp", "default", "My App", "test", null, null, List.of());
        service.register(spec);

        assertThatThrownBy(() -> service.register(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myapp");
    }

    @Test
    void deregister_removesAppAndPools() {
        var spec = new ApplicationSpec("scaffold", "default", "Scaffold", "Dev", null, null,
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
        var spec = new ApplicationSpec("scaffold", "default", "Scaffold", "Dev", null, null,
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
        service.register(new ApplicationSpec("app1", "default", "App 1", "first", null, null, List.of()));
        service.register(new ApplicationSpec("app2", "default", "App 2", "second", null, null, List.of()));

        var apps = service.list();

        assertThat(apps).hasSize(2);
        assertThat(apps).extracting(ApplicationInfo::name).containsExactlyInAnyOrder("app1", "app2");
    }

    @Test
    void heartbeat_updatesAppEntry() {
        service.register(new ApplicationSpec("myapp", "default", "My App", "test", null, null, List.of()));

        service.heartbeat("myapp");

        var entry = registryService.resolve("myapp");
        assertThat(entry).isPresent();
    }

    @Test
    void register_multipleAppsIsolatedByNamespace() {
        service.register(new ApplicationSpec("app-a", "default", "App A", "", null, null,
                                             List.of(new ApplicationSpec.PoolSpec("worker", 0, 5))));
        service.register(new ApplicationSpec("app-b", "default", "App B", "", null, null,
                                             List.of(new ApplicationSpec.PoolSpec("worker", 0, 3))));

        var appARels = registryService.relationships("app-a");
        var appBRels = registryService.relationships("app-b");

        assertThat(appARels).hasSize(1);
        assertThat(appBRels).hasSize(1);
        assertThat(appARels.get(0).targetId()).isEqualTo("app-a/worker");
        assertThat(appBRels.get(0).targetId()).isEqualTo("app-b/worker");
    }

    @Test
    void register_withExplicitNamespace_usesItInRegistryEntry() {
        var spec = new ApplicationSpec("myapp", "production", "My App", "desc", null, null, List.of());

        service.register(spec);

        var entries = registryService.discover(new RegistryQuery("default", "application", "production"));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).namespace()).isEqualTo("production");
    }

    @Test
    void register_withNullNamespace_defaultsToDefault() {
        var spec = new ApplicationSpec("myapp", null, "My App", "desc", null, null, List.of());

        service.register(spec);

        var entries = registryService.discover(new RegistryQuery("default", "application", "default"));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).namespace()).isEqualTo("default");
    }

    @Test
    void register_namespaceCarriedToApplicationInfo() {
        var spec = new ApplicationSpec("myapp", "staging", "My App", "desc", null, null, List.of());

        service.register(spec);
        var info = service.get("myapp");

        assertThat(info).isPresent();
        assertThat(info.get().namespace()).isEqualTo("staging");
    }

    @Test
    void list_filtersByNamespace() {
        service.register(new ApplicationSpec("app1", "prod", "App 1", "", null, null, List.of()));
        service.register(new ApplicationSpec("app2", "staging", "App 2", "", null, null, List.of()));
        service.register(new ApplicationSpec("app3", "prod", "App 3", "", null, null, List.of()));

        var prodApps = service.list("prod");

        assertThat(prodApps).hasSize(2);
        assertThat(prodApps).extracting(ApplicationInfo::name).containsExactlyInAnyOrder("app1", "app3");
    }

    @Test
    void update_changesDisplayNamePreservesTimestamps() {
        var spec                 = new ApplicationSpec("myapp", "default", "Original", "desc", null, null, List.of());
        var original             = service.register(spec);
        var originalRegisteredAt = original.registeredAt();

        var updated = service.update("myapp",
                                     new ApplicationSpec("myapp", "default", "Updated", "new desc", null, null, List.of()));

        assertThat(updated.displayName()).isEqualTo("Updated");
        assertThat(updated.description()).isEqualTo("new desc");
        assertThat(updated.registeredAt()).isEqualTo(originalRegisteredAt);
    }

    @Test
    void update_addsNewPool() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null,
                                             List.of(new ApplicationSpec.PoolSpec("review", 0, 3))));

        service.update("myapp", new ApplicationSpec("myapp", "default", "App", "", null, null,
                                                    List.of(
                                                            new ApplicationSpec.PoolSpec("review", 0, 3),
                                                            new ApplicationSpec.PoolSpec("code", 0, 5)
                                                           )));

        assertThat(poolRegistry.get("myapp/code")).isPresent();
        assertThat(poolRegistry.get("myapp/review")).isPresent();
    }

    @Test
    void update_removesOldPool() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null,
                                             List.of(
                                                     new ApplicationSpec.PoolSpec("review", 0, 3),
                                                     new ApplicationSpec.PoolSpec("code", 0, 5)
                                                    )));

        service.update("myapp", new ApplicationSpec("myapp", "default", "App", "", null, null,
                                                    List.of(new ApplicationSpec.PoolSpec("review", 0, 3))));

        assertThat(poolRegistry.get("myapp/code")).isEmpty();
        assertThat(poolRegistry.get("myapp/review")).isPresent();
    }

    @Test
    void update_changesPoolScaling() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null,
                                             List.of(new ApplicationSpec.PoolSpec("review", 0, 3))));

        service.update("myapp", new ApplicationSpec("myapp", "default", "App", "", null, null,
                                                    List.of(new ApplicationSpec.PoolSpec("review", 1, 10))));

        var def = poolRegistry.get("myapp/review");
        assertThat(def).isPresent();
        assertThat(def.get().pool().minActive()).isEqualTo(1);
        assertThat(def.get().pool().maxActive()).isEqualTo(10);
    }

    @Test
    void update_nonExistentThrows() {
        assertThatThrownBy(() -> service.update("nonexistent",
                                                new ApplicationSpec("nonexistent", "default", "X", "", null, null, List.of())))
                .isInstanceOf(jakarta.ws.rs.WebApplicationException.class);
    }

    @Test
    void update_nameMismatchThrows() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null, List.of()));

        assertThatThrownBy(() -> service.update("myapp",
                                                new ApplicationSpec("different", "default", "App", "", null, null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void update_preservesHealthState() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null, List.of()));

        var updated = service.update("myapp",
                                     new ApplicationSpec("myapp", "default", "Updated", "", null, null, List.of()));

        assertThat(updated.health()).isEqualTo(HealthStatus.HEALTHY);
    }

    @Test
    void suspend_setsHealthToSuspended() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null, List.of()));

        service.suspend("myapp");

        var entry = registryService.resolve("myapp");
        assertThat(entry).isPresent();
        assertThat(entry.get().health()).isEqualTo(HealthStatus.SUSPENDED);
    }

    @Test
    void suspend_alreadySuspendedIsNoOp() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null, List.of()));

        service.suspend("myapp");
        service.suspend("myapp");

        var entry = registryService.resolve("myapp");
        assertThat(entry).isPresent();
        assertThat(entry.get().health()).isEqualTo(HealthStatus.SUSPENDED);
    }

    @Test
    void suspend_nonExistentThrows() {
        assertThatThrownBy(() -> service.suspend("nonexistent"))
                .isInstanceOf(jakarta.ws.rs.WebApplicationException.class);
    }

    @Test
    void resume_restoresHealthToHealthy() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null, List.of()));
        service.suspend("myapp");

        service.resume("myapp");

        var entry = registryService.resolve("myapp");
        assertThat(entry).isPresent();
        assertThat(entry.get().health()).isEqualTo(HealthStatus.HEALTHY);
    }

    @Test
    void resume_nonSuspendedIsNoOp() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null, List.of()));

        service.resume("myapp");

        var entry = registryService.resolve("myapp");
        assertThat(entry).isPresent();
        assertThat(entry.get().health()).isEqualTo(HealthStatus.HEALTHY);
    }

    @Test
    void resume_nonExistentThrows() {
        assertThatThrownBy(() -> service.resume("nonexistent"))
                .isInstanceOf(jakarta.ws.rs.WebApplicationException.class);
    }

    @Test
    void suspend_preservesPoolCapacity() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null,
                                             List.of(new ApplicationSpec.PoolSpec("review", 2, 5))));

        service.suspend("myapp");

        var def = poolRegistry.get("myapp/review");
        assertThat(def).isPresent();
        assertThat(def.get().pool().minActive()).isEqualTo(2);
        assertThat(def.get().pool().maxActive()).isEqualTo(5);
    }

    @Test
    void resume_poolCapacityUnchanged() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null,
                                             List.of(new ApplicationSpec.PoolSpec("review", 2, 5))));
        service.suspend("myapp");
        service.resume("myapp");

        var def = poolRegistry.get("myapp/review");
        assertThat(def).isPresent();
        assertThat(def.get().pool().minActive()).isEqualTo(2);
        assertThat(def.get().pool().maxActive()).isEqualTo(5);
    }

    @Test
    void suspend_applicationInfoReflectsSuspendedHealth() {
        service.register(new ApplicationSpec("myapp", "default", "App", "", null, null, List.of()));
        service.suspend("myapp");

        var info = service.get("myapp");

        assertThat(info).isPresent();
        assertThat(info.get().health()).isEqualTo(HealthStatus.SUSPENDED);
    }

    @Test
    void register_withImageAndConfig_storedInMetadata() {
        var spec = new ApplicationSpec("myapp", "default", "App", "desc",
                                       "ghcr.io/casehubio/myapp:latest", Map.of("LOG_LEVEL", "DEBUG"), List.of());

        service.register(spec);
        var info = service.get("myapp");

        assertThat(info).isPresent();
        assertThat(info.get().image()).isEqualTo("ghcr.io/casehubio/myapp:latest");
        assertThat(info.get().config()).containsEntry("LOG_LEVEL", "DEBUG");
    }

    @Test
    void register_withoutImageOrConfig_defaultsToNullAndEmpty() {
        var spec = new ApplicationSpec("myapp", "default", "App", "desc",
                                       null, null, List.of());

        service.register(spec);
        var info = service.get("myapp");

        assertThat(info).isPresent();
        assertThat(info.get().image()).isNull();
        assertThat(info.get().config()).isEmpty();
    }

    @Test
    void update_changesImageAndConfig() {
        service.register(new ApplicationSpec("myapp", "default", "App", "",
                                             "old:v1", Map.of("A", "1"), List.of()));

        service.update("myapp", new ApplicationSpec("myapp", "default", "App", "",
                                                    "new:v2", Map.of("B", "2"), List.of()));

        var info = service.get("myapp");
        assertThat(info).isPresent();
        assertThat(info.get().image()).isEqualTo("new:v2");
        assertThat(info.get().config()).containsEntry("B", "2");
        assertThat(info.get().config()).doesNotContainKey("A");
    }
}
