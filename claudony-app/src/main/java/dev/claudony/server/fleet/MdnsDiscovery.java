package dev.claudony.server.fleet;

import dev.claudony.config.ClaudonyConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Discovers Claudony peers on the local network via mDNS (Bonjour/Zeroconf).
 * Advertises this instance as {@code _claudony._tcp.local.} and discovers others.
 *
 * <p>Disabled by default — enable with {@code claudony.mdns-discovery=true}.
 * Works on home/office LANs. Does NOT work across the internet, on VPNs,
 * or on Docker networks without multicast support.</p>
 *
 * <p><strong>Status:</strong> scaffold only — advertising and discovery are stubbed.
 * Full Vert.x mDNS implementation is a follow-on task once the API integration
 * is validated against this version of Quarkus.</p>
 */
@ApplicationScoped
public class MdnsDiscovery implements PeerDiscoverySource {

    private static final Logger LOG = Logger.getLogger(MdnsDiscovery.class);
    static final String SERVICE_TYPE = "_claudony._tcp.local.";

    @Inject ClaudonyConfig config;
    @Inject PeerRegistry registry;

    /** Package-private constructor for unit tests. */
    MdnsDiscovery(ClaudonyConfig config, PeerRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    /** CDI no-arg constructor. */
    MdnsDiscovery() {}

    @PostConstruct
    void init() {
        if (!config.mdnsDiscovery()) {
            LOG.debug("Fleet mDNS discovery disabled (claudony.mdns-discovery=false)");
            return;
        }
        try {
            startAdvertising();
            startDiscovering();
        } catch (Exception e) {
            LOG.warnf("Fleet mDNS unavailable — %s. " +
                    "This is normal on VPNs, Docker networks without multicast, or some cloud environments. " +
                    "Static config and manual peer registration still work.", e.getMessage());
        }
    }

    @Override
    public String name() { return "mdns"; }

    @Override
    public void discover(PeerRegistry registry) {
        // mDNS discovery is continuous via listener callbacks, not a one-shot call.
        // Peers discovered via mDNS are added in startDiscovering().
    }

    private void startAdvertising() {
        // Stub — full implementation via Vert.x ServiceDiscovery in follow-on task.
        // Will advertise: name=config.name(), type=SERVICE_TYPE, port=config.port()
        LOG.debugf("Fleet mDNS: would advertise '%s' on %s (implementation pending)",
                config.name(), SERVICE_TYPE);
    }

    private void startDiscovering() {
        // Stub — full implementation via Vert.x ServiceDiscovery in follow-on task.
        // Will add discovered peers: registry.addPeer(id, url, name, DiscoverySource.MDNS, TerminalMode.DIRECT)
        LOG.debugf("Fleet mDNS: would discover peers of type %s (implementation pending)", SERVICE_TYPE);
    }
}
