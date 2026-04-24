package dev.claudony.server.fleet;

import dev.claudony.config.ClaudonyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MdnsDiscoveryTest {

    @TempDir Path tempDir;

    @Test
    void disabledByDefault_doesNothing() {
        var config = mock(ClaudonyConfig.class);
        when(config.mdnsDiscovery()).thenReturn(false);
        when(config.fleetKey()).thenReturn(Optional.empty());
        when(config.name()).thenReturn("Test");
        var registry = new PeerRegistry(tempDir);

        var discovery = new MdnsDiscovery(config, registry);
        discovery.init(); // must not throw, must not add any peers

        assertThat(registry.getAllPeers()).isEmpty();
    }

    @Test
    void failsGracefully_whenMdnsUnavailable() {
        var config = mock(ClaudonyConfig.class);
        when(config.mdnsDiscovery()).thenReturn(true);
        when(config.fleetKey()).thenReturn(Optional.empty());
        when(config.name()).thenReturn("Test");
        var registry = new PeerRegistry(tempDir);

        // Must not throw even if mDNS registration fails (e.g. no multicast network)
        var discovery = new MdnsDiscovery(config, registry);
        discovery.init(); // no assertion needed beyond no exception
    }
}
