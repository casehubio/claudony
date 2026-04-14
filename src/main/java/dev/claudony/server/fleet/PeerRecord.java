package dev.claudony.server.fleet;

import java.time.Instant;

/** Immutable snapshot of a peer — used in REST responses and returned from PeerRegistry public API. */
public record PeerRecord(
        String id,
        String url,
        String name,
        DiscoverySource source,
        TerminalMode terminalMode,
        PeerHealth health,
        CircuitState circuitState,
        Instant lastSeen,
        boolean stale,
        int sessionCount) {}
