package dev.claudony.server.fleet;

import dev.claudony.server.model.SessionResponse;
import java.time.Instant;
import java.util.List;

/**
 * Mutable internal state for a peer in the registry.
 * Package-private — only PeerRegistry and other fleet classes use this directly.
 * External callers get PeerRecord snapshots via PeerEntry.toRecord().
 */
final class PeerEntry {

    static final long INITIAL_BACKOFF_MS = 30_000L;
    static final long MAX_BACKOFF_MS = 300_000L; // 5 minutes
    static final int FAILURE_THRESHOLD = 3;

    final String id;
    final String url;
    volatile String name;
    final DiscoverySource source;
    volatile TerminalMode terminalMode;
    volatile PeerHealth health = PeerHealth.UNKNOWN;
    volatile CircuitState circuitState = CircuitState.CLOSED;
    volatile int consecutiveFailures = 0;
    volatile Instant lastSeen = null;
    volatile Instant circuitOpenedAt = null;
    volatile long currentBackoffMs = INITIAL_BACKOFF_MS;
    volatile List<SessionResponse> cachedSessions = List.of();

    PeerEntry(String id, String url, String name, DiscoverySource source, TerminalMode terminalMode) {
        this.id = id;
        this.url = url;
        this.name = name;
        this.source = source;
        this.terminalMode = terminalMode;
    }

    PeerRecord toRecord() {
        return new PeerRecord(id, url, name, source, terminalMode, health, circuitState,
                lastSeen, health == PeerHealth.DOWN && !cachedSessions.isEmpty(), cachedSessions.size());
    }

    /** Record a successful peer call — resets circuit to CLOSED. */
    void recordSuccess() {
        consecutiveFailures = 0;
        currentBackoffMs = INITIAL_BACKOFF_MS;
        circuitState = CircuitState.CLOSED;
        health = PeerHealth.UP;
        lastSeen = Instant.now();
    }

    /** Record a failed peer call — may open circuit after threshold. */
    void recordFailure() {
        consecutiveFailures++;
        health = PeerHealth.DOWN;
        if (consecutiveFailures >= FAILURE_THRESHOLD && circuitState == CircuitState.CLOSED) {
            circuitState = CircuitState.OPEN;
            circuitOpenedAt = Instant.now();
        }
    }

    /**
     * Returns true if a health check should be attempted now.
     * CLOSED: always. OPEN: only after backoff elapses (transitions to HALF_OPEN). HALF_OPEN: always.
     */
    boolean shouldAttemptHealthCheck() {
        return switch (circuitState) {
            case CLOSED, HALF_OPEN -> true;
            case OPEN -> {
                var elapsed = Instant.now().toEpochMilli() - circuitOpenedAt.toEpochMilli();
                if (elapsed >= currentBackoffMs) {
                    circuitState = CircuitState.HALF_OPEN;
                    currentBackoffMs = Math.min(currentBackoffMs * 2, MAX_BACKOFF_MS);
                    yield true;
                }
                yield false;
            }
        };
    }
}
