package dev.claudony.server.auth;

import dev.claudony.config.ClaudonyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ApiKeyServiceTest {

    @TempDir
    Path tmp;

    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        var config = Mockito.mock(ClaudonyConfig.class);
        Mockito.when(config.credentialsFile())
               .thenReturn(tmp.resolve("credentials.json").toString());
        Mockito.when(config.agentApiKey()).thenReturn(Optional.empty());
        service = new ApiKeyService(config);
    }

    @Test
    void serverGeneratesKeyWhenNoneConfigured() {
        service.initServer();
        assertTrue(service.getKey().isPresent());
        assertTrue(service.getKey().get().matches("claudony-[a-f0-9]{32}"));
    }

    @Test
    void serverPersistsGeneratedKeyToFile() throws Exception {
        service.initServer();
        var keyFile = tmp.resolve("api-key");
        assertTrue(Files.exists(keyFile));
        assertEquals(service.getKey().get(), Files.readString(keyFile).strip());
    }

    @Test
    void serverSetsFilePermissionsTo600() throws Exception {
        service.initServer();
        var keyFile = tmp.resolve("api-key");
        var perms = Files.getPosixFilePermissions(keyFile);
        assertEquals(PosixFilePermissions.fromString("rw-------"), perms);
    }

    @Test
    void serverLoadsExistingKeyFromFileWithoutGenerating() throws Exception {
        var keyFile = tmp.resolve("api-key");
        Files.writeString(keyFile, "claudony-existingkey");

        service.initServer();

        assertEquals("claudony-existingkey", service.getKey().get());
        assertEquals("claudony-existingkey", Files.readString(keyFile).strip());
    }

    @Test
    void configKeyWinsOverFile() throws Exception {
        var configWithKey = Mockito.mock(ClaudonyConfig.class);
        Mockito.when(configWithKey.credentialsFile())
               .thenReturn(tmp.resolve("credentials.json").toString());
        Mockito.when(configWithKey.agentApiKey()).thenReturn(Optional.of("config-wins"));
        var svc = new ApiKeyService(configWithKey);

        Files.writeString(tmp.resolve("api-key"), "file-loses");

        svc.initServer();

        assertEquals("config-wins", svc.getKey().get());
    }

    @Test
    void agentReturnsEmptyWhenNoKeyFound() {
        service.initAgent();
        assertTrue(service.getKey().isEmpty());
    }

    @Test
    void agentLoadsKeyFromFile() throws Exception {
        Files.writeString(tmp.resolve("api-key"), "claudony-fromfile");

        service.initAgent();

        assertEquals("claudony-fromfile", service.getKey().get());
    }

    @Test
    void serverGeneratesKeyWhenFileIsBlank() throws Exception {
        Files.writeString(tmp.resolve("api-key"), "   ");

        service.initServer();

        // Blank file is treated as absent — key should be generated
        assertTrue(service.getKey().isPresent());
        assertTrue(service.getKey().get().matches("claudony-[a-f0-9]{32}"));
    }
}
