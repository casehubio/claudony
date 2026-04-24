package dev.claudony.server.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.claudony.server.model.SessionResponse;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PeerRegistry {

    private static final Logger LOG = Logger.getLogger(PeerRegistry.class);
    private static final int SOURCE_PRIORITY_CONFIG = 0;
    private static final int SOURCE_PRIORITY_MANUAL = 1;
    private static final int SOURCE_PRIORITY_MDNS = 2;

    private final ConcurrentHashMap<String, PeerEntry> peers = new ConcurrentHashMap<>();
    private final Path peersFile;
    private final ObjectMapper mapper;

    /** CDI constructor — uses ~/.claudony/peers.json. */
    PeerRegistry() {
        this.peersFile = Path.of(System.getProperty("user.home"), ".claudony", "peers.json");
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    /** Package-private constructor for unit tests — uses a temp directory. */
    PeerRegistry(Path configDir) {
        this.peersFile = configDir.resolve("peers.json");
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    @PostConstruct
    void loadPersistedPeers() {
        if (!Files.exists(peersFile)) return;
        try {
            var json = Files.readString(peersFile);
            var records = mapper.readValue(json, PeerRecord[].class);
            for (var record : records) {
                if (record.source() == DiscoverySource.CONFIG) continue;
                var entry = new PeerEntry(record.id(), record.url(), record.name(),
                        record.source(), record.terminalMode());
                peers.put(record.id(), entry);
            }
            LOG.infof("Fleet: loaded %d peers from %s", peers.size(), peersFile);
        } catch (Exception e) {
            LOG.warnf("Fleet: could not load peers from %s: %s — starting with empty peer list",
                    peersFile, e.getMessage());
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Adds a peer. Deduplicates by URL — higher trust source wins.
     * Priority: CONFIG (0) beats MANUAL (1) beats MDNS (2).
     */
    public synchronized void addPeer(String id, String url, String name,
                                     DiscoverySource source, TerminalMode terminalMode) {
        var existing = peers.values().stream()
                .filter(e -> e.url.equals(url))
                .findFirst();

        if (existing.isPresent()) {
            if (sourcePriority(source) < sourcePriority(existing.get().source)) {
                peers.remove(existing.get().id);
            } else {
                return; // existing has equal or higher trust — keep it
            }
        }

        peers.put(id, new PeerEntry(id, url, name, source, terminalMode));
        if (source != DiscoverySource.CONFIG) {
            persistAsync();
        }
    }

    /**
     * Removes a peer. CONFIG peers cannot be removed — returns false.
     */
    public synchronized boolean removePeer(String id) {
        var entry = peers.get(id);
        if (entry == null) return false;
        if (entry.source == DiscoverySource.CONFIG) return false;
        peers.remove(id);
        persistAsync();
        return true;
    }

    public Optional<PeerRecord> findById(String id) {
        return Optional.ofNullable(peers.get(id)).map(PeerEntry::toRecord);
    }

    public List<PeerRecord> getAllPeers() {
        return peers.values().stream().map(PeerEntry::toRecord).toList();
    }

    /**
     * Returns PeerRecords for peers eligible for federation calls (CLOSED or HALF_OPEN circuit).
     */
    public List<PeerRecord> getHealthyPeers() {
        return peers.values().stream()
                .filter(e -> e.circuitState != CircuitState.OPEN)
                .map(PeerEntry::toRecord)
                .toList();
    }

    /** Returns all PeerEntry objects — package-private, used by health check loop inside this package. */
    List<PeerEntry> getAllEntries() {
        return List.copyOf(peers.values());
    }

    public boolean updatePeer(String id, String name, TerminalMode terminalMode) {
        var entry = peers.get(id);
        if (entry == null) return false;
        if (name != null && !name.isBlank()) entry.name = name;
        if (terminalMode != null) entry.terminalMode = terminalMode;
        persistAsync();
        return true;
    }

    public void recordSuccess(String id) {
        var entry = peers.get(id);
        if (entry != null) entry.recordSuccess();
    }

    public void recordFailure(String id) {
        var entry = peers.get(id);
        if (entry != null) entry.recordFailure();
    }

    public void updateCachedSessions(String id, List<SessionResponse> sessions) {
        var entry = peers.get(id);
        if (entry != null) entry.cachedSessions = List.copyOf(sessions);
    }

    public List<SessionResponse> getCachedSessions(String id) {
        var entry = peers.get(id);
        return entry == null ? List.of() : entry.cachedSessions;
    }

    // ─── Persistence ──────────────────────────────────────────────────────────

    private volatile Thread lastPersistThread;

    private void persistAsync() {
        lastPersistThread = Thread.ofVirtual().start(this::persist);
    }

    /**
     * Joins the last in-flight persistAsync() thread.
     * Package-private for use in unit test @AfterEach to drain before @TempDir cleanup.
     */
    void drainAsync() {
        Thread t = lastPersistThread;
        if (t != null) {
            try { t.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Synchronously writes non-CONFIG peers to peers.json (atomic write).
     * Package-private so unit tests can call directly instead of going through persistAsync().
     */
    void persist() {
        try {
            var toSave = peers.values().stream()
                    .filter(e -> e.source != DiscoverySource.CONFIG)
                    .map(PeerEntry::toRecord)
                    .toList();
            var json = mapper.writeValueAsString(toSave);
            Files.createDirectories(peersFile.getParent());
            var tmp = peersFile.resolveSibling("peers.json.tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, peersFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warnf("Fleet: could not persist peers to %s: %s", peersFile, e.getMessage());
        }
    }

    private static int sourcePriority(DiscoverySource source) {
        return switch (source) {
            case CONFIG -> SOURCE_PRIORITY_CONFIG;
            case MANUAL -> SOURCE_PRIORITY_MANUAL;
            case MDNS -> SOURCE_PRIORITY_MDNS;
        };
    }
}
