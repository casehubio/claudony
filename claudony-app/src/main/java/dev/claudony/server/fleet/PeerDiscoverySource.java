package dev.claudony.server.fleet;

/** Pluggable source of peer discovery. Multiple implementations feed the same PeerRegistry. */
public interface PeerDiscoverySource {
    String name();
    void discover(PeerRegistry registry);
}
