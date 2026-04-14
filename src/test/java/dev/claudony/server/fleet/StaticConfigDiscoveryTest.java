package dev.claudony.server.fleet;

import dev.claudony.config.ClaudonyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaticConfigDiscoveryTest {

    @TempDir Path tempDir;

    private PeerRegistry registry() { return new PeerRegistry(tempDir); }

    private ClaudonyConfig config(String peers) {
        var c = mock(ClaudonyConfig.class);
        when(c.peers()).thenReturn(peers == null ? Optional.empty() : Optional.of(peers));
        when(c.fleetKey()).thenReturn(Optional.empty());
        when(c.name()).thenReturn("Test");
        return c;
    }

    @Test
    void emptyPeersAddsNoPeers() {
        var registry = registry();
        new StaticConfigDiscovery(config(""), registry).discover(registry);
        assertThat(registry.getAllPeers()).isEmpty();
    }

    @Test
    void absentPeersAddsNoPeers() {
        var registry = registry();
        new StaticConfigDiscovery(config(null), registry).discover(registry);
        assertThat(registry.getAllPeers()).isEmpty();
    }

    @Test
    void singlePeerAdded() {
        var registry = registry();
        new StaticConfigDiscovery(config("http://mac-mini:7777"), registry).discover(registry);
        assertThat(registry.getAllPeers()).hasSize(1);
        assertThat(registry.getAllPeers().get(0).url()).isEqualTo("http://mac-mini:7777");
        assertThat(registry.getAllPeers().get(0).source()).isEqualTo(DiscoverySource.CONFIG);
    }

    @Test
    void multiplePeersCommaSeparated() {
        var registry = registry();
        new StaticConfigDiscovery(config("http://a:7777,http://b:7777,http://c:7777"), registry).discover(registry);
        assertThat(registry.getAllPeers()).hasSize(3);
    }

    @Test
    void blanksInCommaSeparatedListSkipped() {
        var registry = registry();
        new StaticConfigDiscovery(config("http://a:7777, , http://b:7777"), registry).discover(registry);
        assertThat(registry.getAllPeers()).hasSize(2);
    }

    @Test
    void configPeersHaveDirectTerminalModeByDefault() {
        var registry = registry();
        new StaticConfigDiscovery(config("http://peer:7777"), registry).discover(registry);
        assertThat(registry.getAllPeers().get(0).terminalMode()).isEqualTo(TerminalMode.DIRECT);
    }
}
