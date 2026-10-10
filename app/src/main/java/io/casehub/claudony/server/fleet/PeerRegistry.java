package io.casehub.claudony.server.fleet;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.claudony.server.model.SessionResponse;
import io.casehub.platform.api.registry.HealthStatus;
import io.casehub.platform.api.registry.RegistryEntry;
import io.casehub.platform.api.registry.RegistryService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final RegistryService registryService;


    @jakarta.inject.Inject
    PeerRegistry(RegistryService registryService) {
        this.peersFile       = Path.of(System.getProperty("user.home"), ".claudony", "peers.json");
        this.mapper          = new ObjectMapper().findAndRegisterModules();
        this.registryService = registryService;
    }

    PeerRegistry(Path configDir, RegistryService registryService) {
        this.peersFile       = configDir.resolve("peers.json");
        this.mapper          = new ObjectMapper().findAndRegisterModules();
        this.registryService = registryService;
    }

    @PostConstruct
    void loadPersistedPeers() {
        if (!Files.exists(peersFile)) {return;}
        try {
            var json    = Files.readString(peersFile);
            var records = mapper.readValue(json, PeerRecord[].class);
            for (var record : records) {
                if (record.source() == DiscoverySource.CONFIG) {continue;}
                var entry = new PeerEntry(record.id(), record.url(), record.name(),
                                          record.source(), record.terminalMode());
                peers.put(record.id(), entry);
                registryService.register(toRegistryEntry(record.id(), record.url(),
                                                         record.name(), record.source(), record.terminalMode()));
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
                registryService.deregister(existing.get().id);
            } else {
                return;
            }
        }

        peers.put(id, new PeerEntry(id, url, name, source, terminalMode));
        registryService.register(toRegistryEntry(id, url, name, source, terminalMode));
        if (source != DiscoverySource.CONFIG) {
            persistAsync();
        }
    }

    /**
     * Removes a peer. CONFIG peers cannot be removed — returns false.
     */
    public synchronized boolean removePeer(String id) {
        var entry = peers.get(id);
        if (entry == null) {return false;}
        if (entry.source == DiscoverySource.CONFIG) {return false;}
        peers.remove(id);
        registryService.deregister(id);
        persistAsync();
        return true;
    }

    public Optional<PeerRecord> findById(String id) {
        var entry = peers.get(id);
        if (entry == null) {return Optional.empty();}
        return Optional.of(entry.toRecord(resolveHealth(id)));
    }

    public List<PeerRecord> getAllPeers() {
        return peers.values().stream()
                    .map(e -> e.toRecord(resolveHealth(e.id)))
                    .toList();
    }

    /**
     * Returns PeerRecords for peers eligible for federation calls (CLOSED or HALF_OPEN circuit).
     */
    public List<PeerRecord> getHealthyPeers() {
        return peers.values().stream()
                    .filter(e -> e.circuitState != CircuitState.OPEN)
                    .map(e -> e.toRecord(resolveHealth(e.id)))
                    .toList();
    }

    /** Returns all PeerEntry objects — package-private, used by health check loop inside this package. */
    public List<PeerEntry> getAllEntries() {
        return List.copyOf(peers.values());
    }

    public boolean updatePeer(String id, String name, TerminalMode terminalMode) {
        var entry = peers.get(id);
        if (entry == null) {return false;}
        if (name != null && !name.isBlank()) {entry.name = name;}
        if (terminalMode != null) {entry.terminalMode = terminalMode;}
        registryService.resolve(id).ifPresent(regEntry ->
                                                      registryService.register(new RegistryEntry(
                                                              regEntry.id(), regEntry.type(), regEntry.namespace(), regEntry.tenancyId(),
                                                              Map.of("url", entry.url, "name", entry.name,
                                                                     "source", entry.source.name(),
                                                                     "terminalMode", entry.terminalMode.name()),
                                                              regEntry.registeredAt(), regEntry.lastHeartbeat(), regEntry.ttl(), regEntry.health())));
        persistAsync();
        return true;
    }

    public void recordSuccess(String id) {
        var entry = peers.get(id);
        if (entry != null) {
            entry.recordSuccess();
            registryService.resolve(id).ifPresent(e ->
                                                          registryService.register(e.withHealth(HealthStatus.HEALTHY)));
        }
    }

    public void recordFailure(String id) {
        var entry = peers.get(id);
        if (entry != null) {
            entry.recordFailure();
            registryService.resolve(id).ifPresent(e ->
                                                          registryService.register(e.withHealth(HealthStatus.DOWN)));
        }
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

    private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();

    private void persistAsync() {
        Thread t = Thread.ofVirtual().unstarted(() -> {
            try {
                persist();
            } finally {
                activeThreads.remove(Thread.currentThread());
            }
        });
        activeThreads.add(t);
        t.start();
    }

    /**
     * Joins all in-flight persistAsync() threads.
     * Package-private for use in unit test @AfterEach to drain before @TempDir cleanup.
     */
    void drainAsync() {
        for (Thread t : activeThreads) {
            try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
                              .map(e -> e.toRecord(resolveHealth(e.id)))
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

    private RegistryEntry toRegistryEntry(String id, String url, String name,
                                          DiscoverySource source, TerminalMode terminalMode) {
        var now = Instant.now();
        return new RegistryEntry(id, "node", "fleet", "default",
                                 Map.of("url", url, "name", name,
                                        "source", source.name(),
                                        "terminalMode", terminalMode.name()),
                                 now, now, Duration.ofMinutes(5), HealthStatus.DEGRADED);
    }

    static PeerHealth mapHealth(HealthStatus status) {
        return switch (status) {
            case HEALTHY -> PeerHealth.UP;
            case DOWN -> PeerHealth.DOWN;
            case DEGRADED -> PeerHealth.UNKNOWN;
        };
    }

    private PeerHealth resolveHealth(String id) {
        return registryService.resolve(id)
                              .map(e -> mapHealth(e.health()))
                              .orElse(PeerHealth.UNKNOWN);
    }
}
