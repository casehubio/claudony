package io.casehub.claudony.casehub.fleet;

import io.casehub.platform.api.registry.RegistryQuery;
import io.casehub.platform.api.registry.RegistryService;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPoolDefinitionRegistryTest {


    private RegistryService             registryService;
    private AgentPoolDefinitionRegistry registry;

    @BeforeEach
    void setUp() {
        registryService = new InMemoryRegistryService(event -> {});
        registry        = new AgentPoolDefinitionRegistry(registryService);
    }

    @Test
    void registerAndLookup() {
        var def = AgentPoolDefinition.builder()
                .agent("code-reviewer")
                    .workingDir("/reviews")
                .build();

        registry.register(def);
        assertThat(registry.get("code-reviewer")).isPresent().hasValue(def);
    }

    @Test
    void lookupMissingReturnsEmpty() {
        assertThat(registry.get("nonexistent")).isEmpty();
    }

    @Test
    void allDefinitions() {
        var def1 = AgentPoolDefinition.builder().agent("reviewer").build();
        var def2 = AgentPoolDefinition.builder().agent("coder").build();

        registry.register(def1);
        registry.register(def2);

        assertThat(registry.all()).hasSize(2);
        assertThat(registry.all()).containsExactlyInAnyOrder(def1, def2);
    }

    @Test
    void duplicateNameThrows() {
        var def1 = AgentPoolDefinition.builder().agent("reviewer").build();
        var def2 = AgentPoolDefinition.builder()
                .agent("reviewer")
                    .workingDir("/different")
                .build();

        registry.register(def1);
        assertThatThrownBy(() -> registry.register(def2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reviewer");
    }

    @Test
    void names() {
        registry.register(AgentPoolDefinition.builder().agent("a").build());
        registry.register(AgentPoolDefinition.builder().agent("b").build());

        assertThat(registry.names()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void isEmpty() {
        assertThat(registry.isEmpty()).isTrue();
        registry.register(AgentPoolDefinition.builder().agent("a").build());
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void size() {
        assertThat(registry.size()).isZero();
        registry.register(AgentPoolDefinition.builder().agent("x").build());
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void register_createsRegistryEntry() {
        var def = AgentPoolDefinition.builder()
                                     .agent("code-reviewer")
                                     .workingDir("/reviews")
                                     .build();

        registry.register(def);

        var entries = registryService.discover(new RegistryQuery("default", "pool", "fleet"));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).id()).isEqualTo("code-reviewer");
        assertThat(entries.get(0).type()).isEqualTo("pool");
        assertThat(entries.get(0).metadata()).containsEntry("agentName", "code-reviewer");
    }

    @Test
    void registryEntry_updatedOnCapacityChange() {
        var def = AgentPoolDefinition.builder().agent("test").pool()
                                     .minActive(0).maxActive(10).build();
        registry.register(def);

        registry.updateCapacity("test", 2, 20);

        var entries = registryService.discover(new RegistryQuery("default", "pool", "fleet"));
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).metadata()).containsEntry("minActive", "2");
        assertThat(entries.get(0).metadata()).containsEntry("maxActive", "20");
    }


}
