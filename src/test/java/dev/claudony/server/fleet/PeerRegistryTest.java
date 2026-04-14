package dev.claudony.server.fleet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PeerRegistryTest {

    @TempDir
    Path tempDir;

    PeerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry(tempDir);
    }

    /**
     * Drain any in-flight persistAsync() virtual threads before JUnit deletes @TempDir.
     * persistAsync() spawns a virtual thread; without this, cleanup may race with the write.
     */
    @AfterEach
    void drainPersist() {
        registry.persist();
    }

    // ─── Basic CRUD ──────────────────────────────────────────────────────────

    @Test
    void emptyOnStart() {
        assertThat(registry.getAllPeers()).isEmpty();
    }

    @Test
    void addPeerAppearsInList() {
        registry.addPeer("id1", "http://peer-a:7777", "Peer A", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        assertThat(registry.getAllPeers()).hasSize(1);
        assertThat(registry.getAllPeers().get(0).url()).isEqualTo("http://peer-a:7777");
    }

    @Test
    void removePeer() {
        registry.addPeer("id1", "http://peer-a:7777", "Peer A", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        registry.removePeer("id1");
        assertThat(registry.getAllPeers()).isEmpty();
    }

    @Test
    void findPeerById() {
        registry.addPeer("id1", "http://peer-a:7777", "Peer A", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        assertThat(registry.findById("id1")).isPresent();
        assertThat(registry.findById("missing")).isEmpty();
    }

    // ─── Deduplication ───────────────────────────────────────────────────────

    @Test
    void deduplicatesByUrl_configWinsOverManual() {
        registry.addPeer("id1", "http://peer-a:7777", "Config Peer", DiscoverySource.CONFIG, TerminalMode.DIRECT);
        registry.addPeer("id2", "http://peer-a:7777", "Manual Peer", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        assertThat(registry.getAllPeers()).hasSize(1);
        assertThat(registry.getAllPeers().get(0).source()).isEqualTo(DiscoverySource.CONFIG);
    }

    @Test
    void deduplicatesByUrl_mdnsLosesToManual() {
        registry.addPeer("id1", "http://peer-a:7777", "mDNS Peer", DiscoverySource.MDNS, TerminalMode.DIRECT);
        registry.addPeer("id2", "http://peer-a:7777", "Manual Peer", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        assertThat(registry.getAllPeers()).hasSize(1);
        assertThat(registry.getAllPeers().get(0).source()).isEqualTo(DiscoverySource.MANUAL);
    }

    @Test
    void differentUrlsCoexist() {
        registry.addPeer("id1", "http://peer-a:7777", "A", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        registry.addPeer("id2", "http://peer-b:7777", "B", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        assertThat(registry.getAllPeers()).hasSize(2);
    }

    // ─── Config peers cannot be removed ─────────────────────────────────────

    @Test
    void configPeerCannotBeRemoved() {
        registry.addPeer("id1", "http://peer-a:7777", "Config", DiscoverySource.CONFIG, TerminalMode.DIRECT);
        var removed = registry.removePeer("id1");
        assertThat(removed).isFalse();
        assertThat(registry.getAllPeers()).hasSize(1);
    }

    @Test
    void manualPeerCanBeRemoved() {
        registry.addPeer("id1", "http://peer-a:7777", "Manual", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        var removed = registry.removePeer("id1");
        assertThat(removed).isTrue();
        assertThat(registry.getAllPeers()).isEmpty();
    }

    // ─── Health and circuit state ─────────────────────────────────────────

    @Test
    void newPeerHealthIsUnknown() {
        registry.addPeer("id1", "http://peer-a:7777", "A", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        assertThat(registry.getAllPeers().get(0).health()).isEqualTo(PeerHealth.UNKNOWN);
        assertThat(registry.getAllPeers().get(0).circuitState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void recordSuccessUpdatesHealth() {
        registry.addPeer("id1", "http://peer-a:7777", "A", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        registry.recordSuccess("id1");
        assertThat(registry.findById("id1").get().health()).isEqualTo(PeerHealth.UP);
        assertThat(registry.findById("id1").get().circuitState()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void threeFailuresOpenCircuit() {
        registry.addPeer("id1", "http://peer-a:7777", "A", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        registry.recordFailure("id1");
        registry.recordFailure("id1");
        assertThat(registry.findById("id1").get().circuitState()).isEqualTo(CircuitState.CLOSED);
        registry.recordFailure("id1");
        assertThat(registry.findById("id1").get().circuitState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void successAfterOpenResetsToClosedAndClearsFailures() {
        registry.addPeer("id1", "http://peer-a:7777", "A", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        registry.recordFailure("id1");
        registry.recordFailure("id1");
        registry.recordFailure("id1");
        registry.recordSuccess("id1");
        var peer = registry.findById("id1").get();
        assertThat(peer.circuitState()).isEqualTo(CircuitState.CLOSED);
        assertThat(peer.health()).isEqualTo(PeerHealth.UP);
    }

    // ─── Healthy peers for federation ────────────────────────────────────────

    @Test
    void getHealthyPeers_excludesOpenCircuit() {
        registry.addPeer("id1", "http://peer-a:7777", "A", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        registry.addPeer("id2", "http://peer-b:7777", "B", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        registry.recordFailure("id1");
        registry.recordFailure("id1");
        registry.recordFailure("id1");
        assertThat(registry.getHealthyPeers()).hasSize(1);
        assertThat(registry.getHealthyPeers().get(0).id()).isEqualTo("id2");
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    @Test
    void manualPeerPersistedToFile() throws Exception {
        registry.addPeer("id1", "http://peer-a:7777", "A", DiscoverySource.MANUAL, TerminalMode.DIRECT);
        registry.persist(); // synchronous — no Thread.sleep needed
        assertThat(tempDir.resolve("peers.json")).exists();
    }

    @Test
    void configPeerNotPersistedToFile() throws Exception {
        registry.addPeer("id1", "http://peer-a:7777", "Config", DiscoverySource.CONFIG, TerminalMode.DIRECT);
        registry.persist(); // synchronous
        // Config peers are never written to peers.json (they come from config, not file)
        if (tempDir.resolve("peers.json").toFile().exists()) {
            var content = java.nio.file.Files.readString(tempDir.resolve("peers.json"));
            assertThat(content).doesNotContain("http://peer-a:7777");
        }
    }

    @Test
    void corruptedPeersJsonIsIgnored() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("peers.json"), "NOT VALID JSON {{{");
        // Should not throw — graceful recovery
        var freshRegistry = new PeerRegistry(tempDir);
        freshRegistry.loadPersistedPeers();
        assertThat(freshRegistry.getAllPeers()).isEmpty();
    }
}
