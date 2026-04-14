package dev.claudony.server.fleet;

import dev.claudony.config.ClaudonyConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class StaticConfigDiscovery implements PeerDiscoverySource {

    private static final Logger LOG = Logger.getLogger(StaticConfigDiscovery.class);

    @Inject ClaudonyConfig config;
    @Inject PeerRegistry registry;

    /** Package-private constructor for unit tests. */
    StaticConfigDiscovery(ClaudonyConfig config, PeerRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    /** CDI no-arg constructor. */
    StaticConfigDiscovery() {}

    @PostConstruct
    void init() {
        discover(registry);
    }

    @Override
    public String name() { return "static-config"; }

    @Override
    public void discover(PeerRegistry registry) {
        // config.peers() returns Optional<String> — use orElse("") to handle absent case
        var peers = config.peers().orElse("");
        if (peers.isBlank()) return;

        for (var raw : peers.split(",")) {
            var url = raw.strip();
            if (url.isBlank()) continue;
            var id = UUID.randomUUID().toString();
            registry.addPeer(id, url, url, DiscoverySource.CONFIG, TerminalMode.DIRECT);
            LOG.infof("Fleet: registered static peer %s", url);
        }
    }
}
