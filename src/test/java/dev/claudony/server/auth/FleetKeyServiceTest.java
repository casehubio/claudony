package dev.claudony.server.auth;

import dev.claudony.config.ClaudonyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FleetKeyServiceTest {

    @TempDir
    Path tempDir;

    private FleetKeyService service(String configKey) {
        var config = mock(ClaudonyConfig.class);
        when(config.fleetKey()).thenReturn(configKey == null ? Optional.empty() : Optional.of(configKey));
        return new FleetKeyService(config, tempDir.resolve("fleet-key"));
    }

    @Test
    void configKeyTakesPrecedence() {
        var svc = service("config-fleet-key");
        svc.init();
        assertThat(svc.getKey()).contains("config-fleet-key");
    }

    @Test
    void loadsKeyFromFile() throws IOException {
        Files.writeString(tempDir.resolve("fleet-key"), "file-fleet-key");
        var svc = service(null);
        svc.init();
        assertThat(svc.getKey()).contains("file-fleet-key");
    }

    @Test
    void emptyWhenNeitherConfigNorFile() {
        var svc = service(null);
        svc.init();
        assertThat(svc.getKey()).isEmpty();
    }

    @Test
    void generateAndSave_createsFileAndUpdatesKey() throws IOException {
        var svc = service(null);
        svc.init();

        var generated = svc.generateAndSave();

        assertThat(generated).isNotBlank().matches("[A-Za-z0-9_\\-]{43}");
        assertThat(svc.getKey()).contains(generated);
        assertThat(Files.readString(tempDir.resolve("fleet-key")).strip()).isEqualTo(generated);
    }

    @Test
    void blankFileIsIgnored() throws IOException {
        Files.writeString(tempDir.resolve("fleet-key"), "   ");
        var svc = service(null);
        svc.init();
        assertThat(svc.getKey()).isEmpty();
    }

    @Test
    void configKeyWinsOverFile() throws IOException {
        Files.writeString(tempDir.resolve("fleet-key"), "file-key");
        var svc = service("config-key");
        svc.init();
        assertThat(svc.getKey()).contains("config-key");
    }
}
