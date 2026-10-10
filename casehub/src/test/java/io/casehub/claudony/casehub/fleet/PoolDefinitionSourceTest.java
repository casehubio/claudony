package io.casehub.claudony.casehub.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.Test;
import io.casehub.platform.registry.memory.InMemoryRegistryService;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PoolDefinitionSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversManifestAndRegisters() throws Exception {
        var manifestDir = tempDir.resolve("META-INF/pool-definitions");
        Files.createDirectories(manifestDir);

        Files.writeString(manifestDir.resolve("code-reviewer.json"), """
                {
                  "name": "code-reviewer",
                  "recordClass": "test.CodeReviewerPool",
                  "schemaResource": "META-INF/pool-definitions/code-reviewer.schema.json"
                }
                """);

        Files.writeString(manifestDir.resolve("code-reviewer.schema.json"), """
                {
                  "type": "object",
                  "properties": {
                    "working-dir": { "type": "string" },
                    "min-active": { "type": "integer" },
                    "max-active": { "type": "integer" }
                  }
                }
                """);

        var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()});
        var registry = new AgentPoolDefinitionRegistry(new InMemoryRegistryService(event -> {}));
        var source = new PoolDefinitionSource(new ObjectMapper());
        source.discover(cl, registry);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get("code-reviewer")).isPresent();
        var def = registry.get("code-reviewer").get();
        assertThat(def.agent().name()).isEqualTo("code-reviewer");
    }

    @Test
    void emptyClasspathRegistersNothing() throws Exception {
        var emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);
        var cl = new URLClassLoader(new URL[]{emptyDir.toUri().toURL()});
        var registry = new AgentPoolDefinitionRegistry(new InMemoryRegistryService(event -> {}));
        var source = new PoolDefinitionSource(new ObjectMapper());
        source.discover(cl, registry);

        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void multipleManifestsDiscovered() throws Exception {
        var manifestDir = tempDir.resolve("META-INF/pool-definitions");
        Files.createDirectories(manifestDir);

        for (var name : new String[]{"alpha", "beta"}) {
            Files.writeString(manifestDir.resolve(name + ".json"),
                    "{ \"name\": \"" + name + "\", \"recordClass\": \"test." +
                    name.substring(0, 1).toUpperCase() + name.substring(1) + "Pool\" }");
        }

        var cl = new URLClassLoader(new URL[]{tempDir.toUri().toURL()});
        var registry = new AgentPoolDefinitionRegistry(new InMemoryRegistryService(event -> {}));
        new PoolDefinitionSource(new ObjectMapper()).discover(cl, registry);

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.get("alpha")).isPresent();
        assertThat(registry.get("beta")).isPresent();
    }
}
