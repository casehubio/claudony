package io.casehub.claudony.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.claudony.agent.terminal.TerminalAdapterFactory;
import io.casehub.claudony.casehub.CaseLineageQuery;
import io.casehub.claudony.config.ClaudonyConfig;
import io.casehub.claudony.server.expiry.ExpiryPolicyRegistry;
import io.casehub.claudony.server.fleet.FleetKeyClientFilter;
import io.casehub.claudony.server.fleet.PeerClient;
import io.casehub.claudony.server.fleet.PeerRegistry;
import io.casehub.claudony.server.model.CreateSessionRequest;
import io.casehub.claudony.server.model.GitStatusResponse;
import io.casehub.claudony.server.model.PortStatus;
import io.casehub.claudony.server.model.SendInputRequest;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionResponse;
import io.casehub.claudony.server.model.SessionStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ConflictException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class SessionService {

    private static final Logger LOG = Logger.getLogger(SessionService.class);
    private static final List<Integer> DEFAULT_PORTS =
            List.of(3000, 3001, 4000, 4200, 5000, 5173, 8000, 8080, 8081, 8888);

    @Inject ClaudonyConfig config;
    @Inject SessionRegistry registry;
    @Inject TmuxService tmux;
    @Inject TerminalAdapterFactory terminalFactory;
    @Inject PeerRegistry peerRegistry;
    @Inject ExpiryPolicyRegistry policyRegistry;
    @Inject CaseLineageQuery lineageQuery;
    @Inject TenantContext tenantContext;

    public List<SessionResponse> listSessions(boolean localOnly, String caseId) {
        if (caseId != null) {
            return registry.findByCaseId(caseId).stream()
                    .map(s -> SessionResponse.from(s, config.port(), resolvedPolicy(s)))
                    .toList();
        }

        var result = new ArrayList<>(registry.all().stream()
                .map(s -> SessionResponse.from(s, config.port(), resolvedPolicy(s)))
                .toList());

        if (localOnly) return result;

        var allPeers = peerRegistry.getAllPeers();
        if (allPeers.isEmpty()) return result;

        var healthyPeers = peerRegistry.getHealthyPeers();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = healthyPeers.stream()
                    .map(peer -> executor.submit(() -> fetchPeerSessions(peer.url())))
                    .toList();

            for (int i = 0; i < futures.size(); i++) {
                var peer = healthyPeers.get(i);
                try {
                    var sessions = futures.get(i).get(2, TimeUnit.SECONDS);
                    peerRegistry.recordSuccess(peer.id());
                    peerRegistry.updateCachedSessions(peer.id(), sessions);
                    sessions.stream()
                            .map(s -> s.withInstance(peer.url(), peer.name(), false))
                            .forEach(result::add);
                } catch (Exception e) {
                    peerRegistry.recordFailure(peer.id());
                    peerRegistry.getCachedSessions(peer.id()).stream()
                            .map(s -> s.withInstance(peer.url(), peer.name(), true))
                            .forEach(result::add);
                }
            }
        }

        var healthyIds = healthyPeers.stream().map(p -> p.id()).collect(Collectors.toSet());
        allPeers.stream()
                .filter(p -> !healthyIds.contains(p.id()))
                .forEach(peer -> peerRegistry.getCachedSessions(peer.id()).stream()
                        .map(s -> s.withInstance(peer.url(), peer.name(), true))
                        .forEach(result::add));

        return result;
    }

    private List<SessionResponse> fetchPeerSessions(String peerUrl) {
        var client = RestClientBuilder.newBuilder()
                .baseUri(URI.create(peerUrl))
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .register(FleetKeyClientFilter.class)
                .build(PeerClient.class);
        return client.getSessions(true);
    }

    public SessionResponse getSession(String id) {
        return registry.find(id)
                .map(s -> SessionResponse.from(s, config.port(), resolvedPolicy(s)))
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
    }

    public List<?> getLineage(String id) {
        var session = registry.find(id)
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        if (session.caseId().isEmpty()) {
            return List.of();
        }
        UUID caseUuid;
        try {
            caseUuid = UUID.fromString(session.caseId().get());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Session '%s' has non-UUID caseId '%s' — returning empty lineage",
                    id, session.caseId().get());
            return List.of();
        }
        return lineageQuery.findCompletedWorkers(caseUuid);
    }

    public SessionResponse createSession(CreateSessionRequest req, boolean overwrite) {
        var id = UUID.randomUUID().toString();
        var name = config.tmuxPrefix() + req.name();
        var command = req.effectiveCommand(config.claudeCommand());
        var workingDir = (req.workingDir() == null || req.workingDir().isBlank())
                ? config.defaultWorkingDir() + "/" + req.name() : req.workingDir();

        var existingByName = registry.existsByName(name);
        if (existingByName) {
            if (!overwrite) {
                throw new ConflictException("Session '" + name + "' already exists");
            }
            var existingSession = registry.allUnscoped().stream()
                    .filter(s -> s.name().equals(name))
                    .findFirst().orElse(null);
            if (existingSession != null) {
                try {
                    tmux.killSession(name);
                    registry.remove(existingSession.id());
                    LOG.infof("Overwrote existing session '%s'", name);
                } catch (IOException | InterruptedException e) {
                    LOG.warnf("Could not clean up existing session '%s': %s", name, e.getMessage());
                }
            }
        }

        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of(workingDir)); }
        catch (IOException e) {
            LOG.debugf("Could not create working directory '%s': %s", workingDir, e.getMessage());
        }
        var now = Instant.now();
        var session = new Session(id, name, workingDir, command, SessionStatus.IDLE, now, now,
                                  Optional.ofNullable(req.expiryPolicy()), Optional.empty(), Optional.empty(),
                                  tenantContext.currentTenantId());
        try {
            tmux.createSession(name, workingDir, command);
            registry.register(session);
            LOG.infof("Created session '%s' (id=%s)", name, id);
            return SessionResponse.from(session, config.port(), resolvedPolicy(session));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to create session '" + name + "': " + e.getMessage(), e);
        }
    }

    public void deleteSession(String id) {
        var session = registry.find(id)
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        try {
            tmux.killSession(session.name());
            registry.remove(id);
            LOG.infof("Deleted session '%s' (id=%s)", session.name(), id);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to delete session: " + e.getMessage(), e);
        }
    }

    public SessionResponse renameSession(String id, String newName) {
        var session = registry.find(id)
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        try {
            var newTmuxName = config.tmuxPrefix() + newName;
            if (registry.existsByName(newTmuxName)) {
                throw new ConflictException("Session '" + newTmuxName + "' already exists");
            }
            var p = new ProcessBuilder("tmux", "rename-session", "-t", session.name(), newTmuxName)
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("tmux rename-session exited " + exitCode);
            }
            var renamed = new Session(id, newTmuxName, session.workingDir(),
                    session.command(), session.status(), session.createdAt(), Instant.now(),
                    session.expiryPolicy(), session.caseId(), session.roleName(), session.tenancyId());
            registry.register(renamed);
            return SessionResponse.from(renamed, config.port(), resolvedPolicy(renamed));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to rename session '" + session.name() + "': " + e.getMessage(), e);
        }
    }

    public void sendInput(String id, SendInputRequest req) {
        var session = registry.find(id)
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        try {
            tmux.sendKeys(session.name(), req.text());
            registry.touch(id);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to send input to session '" + session.name() + "': " + e.getMessage(), e);
        }
    }

    public String getOutput(String id, int lines) {
        var session = registry.find(id)
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        try {
            return tmux.capturePane(session.name(), lines);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to get output from session '" + session.name() + "': " + e.getMessage(), e);
        }
    }

    public void resize(String id, int cols, int rows) {
        var session = registry.find(id)
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        try {
            var p = new ProcessBuilder("tmux", "resize-pane", "-t", session.name(),
                    "-x", String.valueOf(cols), "-y", String.valueOf(rows))
                    .redirectErrorStream(true).start();
            try (var in = p.getInputStream()) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to resize session '" + session.name() + "': " + e.getMessage(), e);
        }
    }

    public void openTerminal(String id) {
        var session = registry.find(id)
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        var adapter = terminalFactory.resolve();
        if (adapter.isEmpty()) {
            throw new RuntimeException("No terminal adapter available on this machine");
        }
        try {
            adapter.get().openSession(session.name());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to open session '" + session.name() + "' in terminal: " + e.getMessage(), e);
        }
    }

    public GitStatusResponse getGitStatus(String id) {
        var session = registry.find(id)
                .orElseThrow(() -> new NotFoundException("Session not found: " + id));
        var dir = session.workingDir();
        if ("unknown".equals(dir)) {
            return GitStatusResponse.notGit();
        }
        try {
            if (!run("git", "-C", dir, "rev-parse", "--git-dir").success()) {
                return GitStatusResponse.notGit();
            }
            var branch = run("git", "-C", dir, "branch", "--show-current").stdout().trim();
            var remoteUrl = run("git", "-C", dir, "remote", "get-url", "origin").stdout().trim();
            var githubRepo = parseGitHubRepo(remoteUrl);
            if (githubRepo == null) {
                return GitStatusResponse.noGitHub(branch);
            }
            var ghResult = run("gh", "pr", "view",
                    "--repo", githubRepo,
                    "--json", "number,title,url,state,statusCheckRollup");
            if (!ghResult.success()) {
                if (ghResult.stdout().contains("no pull requests found") ||
                        ghResult.stderr().contains("no pull requests found")) {
                    return GitStatusResponse.noPr(githubRepo, branch);
                }
                var errMsg = ghResult.stderr().isBlank() ? "gh not available or not authenticated" : ghResult.stderr().trim();
                return GitStatusResponse.error(githubRepo, branch, errMsg);
            }
            var pr = parsePrInfo(ghResult.stdout());
            return GitStatusResponse.withPr(githubRepo, branch, pr);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to get git status for session '" + session.name() + "': " + e.getMessage(), e);
        }
    }

    public List<PortStatus> getServiceHealth(String id) {
        if (registry.find(id).isEmpty()) {
            throw new NotFoundException("Session not found: " + id);
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = DEFAULT_PORTS.stream()
                    .<Callable<PortStatus>>map(port -> () -> checkPort(port))
                    .toList();
            var results = new ArrayList<PortStatus>();
            for (var future : executor.invokeAll(tasks)) {
                var status = future.get();
                if (status.up()) results.add(status);
            }
            results.sort((a, b) -> Integer.compare(a.port(), b.port()));
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to check service health: " + e.getMessage(), e);
        }
    }

    String resolvedPolicy(Session session) {
        return policyRegistry.resolve(session.expiryPolicy().orElse(null)).name();
    }

    private PortStatus checkPort(int port) {
        var start = System.currentTimeMillis();
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
            return new PortStatus(port, true, System.currentTimeMillis() - start);
        } catch (IOException e) {
            return new PortStatus(port, false, 0);
        }
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        boolean success() { return exitCode == 0; }
    }

    private RunResult run(String... cmd) throws IOException, InterruptedException {
        var p = new ProcessBuilder(cmd).start();
        var stdout = new BufferedReader(new InputStreamReader(p.getInputStream()))
                .lines().collect(Collectors.joining("\n"));
        var stderr = new BufferedReader(new InputStreamReader(p.getErrorStream()))
                .lines().collect(Collectors.joining("\n"));
        int exit = p.waitFor();
        return new RunResult(exit, stdout, stderr);
    }

    private String parseGitHubRepo(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return null;
        var url = remoteUrl.trim();
        if (url.startsWith("https://github.com/")) {
            var path = url.substring("https://github.com/".length());
            return path.endsWith(".git") ? path.substring(0, path.length() - 4) : path;
        }
        if (url.startsWith("git@github.com:")) {
            var path = url.substring("git@github.com:".length());
            return path.endsWith(".git") ? path.substring(0, path.length() - 4) : path;
        }
        return null;
    }

    @Inject ObjectMapper MAPPER;

    private GitStatusResponse.PrInfo parsePrInfo(String json) throws IOException {
        var node = MAPPER.readTree(json);
        int number = node.path("number").asInt();
        var title = node.path("title").asText();
        var url = node.path("url").asText();
        var state = node.path("state").asText();
        var checks = node.path("statusCheckRollup");
        int total = 0, passed = 0, failed = 0, pending = 0;
        if (checks.isArray()) {
            for (var check : checks) {
                total++;
                var conclusion = check.path("conclusion").asText("");
                if ("SUCCESS".equalsIgnoreCase(conclusion)) passed++;
                else if ("FAILURE".equalsIgnoreCase(conclusion) || "ERROR".equalsIgnoreCase(conclusion)) failed++;
                else pending++;
            }
        }
        return new GitStatusResponse.PrInfo(number, title, url, state, total, passed, failed, pending);
    }
}
