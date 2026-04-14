package dev.claudony.server.fleet;

/** How a peer was discovered. Priority: CONFIG (highest trust) > MANUAL > MDNS (lowest). */
public enum DiscoverySource { CONFIG, MANUAL, MDNS }
