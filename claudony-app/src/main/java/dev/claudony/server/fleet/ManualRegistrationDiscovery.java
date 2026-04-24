package dev.claudony.server.fleet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

@ApplicationScoped
public class ManualRegistrationDiscovery implements PeerDiscoverySource {

    @Inject PeerRegistry registry;

    @Override
    public String name() { return "manual"; }

    @Override
    public void discover(PeerRegistry registry) {
        // Peers are loaded by PeerRegistry.loadPersistedPeers() at startup.
        // This source is only triggered by explicit REST API calls.
    }

    public PeerRecord addPeer(String url, String name, TerminalMode terminalMode) {
        // Return existing peer if this URL is already registered
        var existing = registry.getAllPeers().stream()
                .filter(p -> p.url().equals(url))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        var id = UUID.randomUUID().toString();
        registry.addPeer(id, url,
                name != null && !name.isBlank() ? name : url,
                DiscoverySource.MANUAL,
                terminalMode != null ? terminalMode : TerminalMode.DIRECT);
        return registry.findById(id).orElseThrow();
    }
}
