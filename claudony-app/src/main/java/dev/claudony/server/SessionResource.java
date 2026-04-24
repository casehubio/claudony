package dev.claudony.server;

import dev.claudony.agent.terminal.TerminalAdapterFactory;
import dev.claudony.config.ClaudonyConfig;
import dev.claudony.server.expiry.ExpiryPolicyRegistry;
import dev.claudony.server.fleet.FleetKeyClientFilter;
import dev.claudony.server.fleet.PeerClient;
import dev.claudony.server.fleet.PeerRegistry;
import dev.claudony.server.model.*;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Path("/api/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class SessionResource {

    private static final Logger LOG = Logger.getLogger(SessionResource.class);

    @Inject ClaudonyConfig config;
    @Inject SessionRegistry registry;
    @Inject TmuxService tmux;
    @Inject TerminalAdapterFactory terminalFactory;
    @Inject PeerRegistry peerRegistry;
    @Inject ExpiryPolicyRegistry policyRegistry;

    @GET
    public List<SessionResponse> list(@QueryParam("local") @DefaultValue("false") boolean localOnly) {
        // Local sessions — always returned
        var result = new ArrayList<>(registry.all().stream()
                .map(s -> SessionResponse.from(s, config.port(), resolvedPolicy(s)))
                .toList());

        if (localOnly) return result;

        var allPeers = peerRegistry.getAllPeers();
        if (allPeers.isEmpty()) return result;

        // Healthy peers (CLOSED/HALF_OPEN): attempt live fetch, fall back to stale cache on failure
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

        // OPEN circuit peers: don't attempt a live call — serve stale cache only
        var healthyIds = healthyPeers.stream().map(p -> p.id()).collect(java.util.stream.Collectors.toSet());
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
        return client.getSessions(true); // local=true prevents recursive federation
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        return registry.find(id)
                .map(s -> Response.ok(SessionResponse.from(s, config.port(), resolvedPolicy(s))).build())
                .orElse(Response.status(404).build());
    }

    @POST
    public Response create(CreateSessionRequest req,
                           @QueryParam("overwrite") @DefaultValue("false") boolean overwrite) {
        var id = UUID.randomUUID().toString();
        var name = config.tmuxPrefix() + req.name();
        var command = req.effectiveCommand(config.claudeCommand());
        var workingDir = (req.workingDir() == null || req.workingDir().isBlank())
                ? config.defaultWorkingDir() : req.workingDir();

        // Duplicate name check
        var existing = registry.all().stream()
                .filter(s -> s.name().equals(name))
                .findFirst();
        if (existing.isPresent()) {
            if (!overwrite) {
                return Response.status(409)
                        .entity("{\"error\":\"Session '" + name + "' already exists\"}")
                        .build();
            }
            // Overwrite: remove existing session first
            try {
                tmux.killSession(name);
                registry.remove(existing.get().id());
                LOG.infof("Overwrote existing session '%s'", name);
            } catch (IOException | InterruptedException e) {
                LOG.warnf("Could not clean up existing session '%s': %s", name, e.getMessage());
            }
        }

        // Ensure the working directory exists
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of(workingDir)); }
        catch (IOException e) {
            LOG.debugf("Could not create working directory '%s': %s", workingDir, e.getMessage());
        }
        var now = Instant.now();
        var session = new Session(id, name, workingDir, command, SessionStatus.IDLE, now, now,
                Optional.ofNullable(req.expiryPolicy()));
        try {
            tmux.createSession(name, workingDir, command);
            registry.register(session);
            LOG.infof("Created session '%s' (id=%s)", name, id);
            return Response.status(201)
                    .entity(SessionResponse.from(session, config.port(), resolvedPolicy(session)))
                    .build();
        } catch (IOException | InterruptedException e) {
            LOG.errorf("Failed to create session '%s': %s", name, e.getMessage());
            return Response.serverError()
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        return registry.find(id).map(session -> {
            try {
                tmux.killSession(session.name());
                registry.remove(id);
                LOG.infof("Deleted session '%s' (id=%s)", session.name(), id);
                return Response.noContent().build();
            } catch (IOException | InterruptedException e) {
                LOG.errorf("Failed to delete session: %s", e.getMessage());
                return Response.serverError().build();
            }
        }).orElse(Response.status(404).build());
    }

    @PATCH
    @Path("/{id}/rename")
    public Response rename(@PathParam("id") String id, @QueryParam("name") String newName) {
        return registry.find(id).map(session -> {
            try {
                var newTmuxName = config.tmuxPrefix() + newName;
                boolean duplicate = registry.all().stream().anyMatch(s -> s.name().equals(newTmuxName));
                if (duplicate) {
                    return Response.status(409)
                            .entity("{\"error\":\"Session '" + newTmuxName + "' already exists\"}")
                            .build();
                }
                var p = new ProcessBuilder("tmux", "rename-session", "-t", session.name(), newTmuxName)
                        .redirectErrorStream(true).start();
                p.getInputStream().transferTo(OutputStream.nullOutputStream());
                int exitCode = p.waitFor();
                if (exitCode != 0) {
                    LOG.errorf("tmux rename-session exited %d for session '%s' -> '%s'", exitCode, session.name(), newTmuxName);
                    return Response.serverError().build();
                }
                var renamed = new Session(id, newTmuxName, session.workingDir(),
                        session.command(), session.status(), session.createdAt(), Instant.now(),
                        session.expiryPolicy());
                registry.register(renamed);
                return Response.ok(SessionResponse.from(renamed, config.port(), resolvedPolicy(renamed))).build();
            } catch (IOException | InterruptedException e) {
                LOG.errorf("Failed to rename session '%s': %s", session.name(), e.getMessage());
                return Response.serverError().build();
            }
        }).orElse(Response.status(404).build());
    }

    @POST
    @Path("/{id}/input")
    @Produces(MediaType.APPLICATION_JSON)
    public Response sendInput(@PathParam("id") String id, SendInputRequest req) {
        return registry.find(id).map(session -> {
            try {
                tmux.sendKeys(session.name(), req.text());
                registry.touch(id);
                return Response.noContent().build();
            } catch (IOException | InterruptedException e) {
                LOG.errorf("Failed to send input to session '%s': %s", session.name(), e.getMessage());
                return Response.serverError().build();
            }
        }).orElse(Response.status(404).build());
    }

    @GET
    @Path("/{id}/output")
    @Produces(MediaType.TEXT_PLAIN)
    public Response getOutput(@PathParam("id") String id,
                              @QueryParam("lines") @DefaultValue("50") int lines) {
        return registry.find(id).map(session -> {
            try {
                var output = tmux.capturePane(session.name(), lines);
                return Response.ok(output).build();
            } catch (IOException | InterruptedException e) {
                LOG.errorf("Failed to get output from session '%s': %s", session.name(), e.getMessage());
                return Response.serverError().build();
            }
        }).orElse(Response.status(404).build());
    }

    @POST
    @Path("/{id}/resize")
    public Response resize(@PathParam("id") String id,
                           @QueryParam("cols") @DefaultValue("80") int cols,
                           @QueryParam("rows") @DefaultValue("24") int rows) {
        return registry.find(id).map(session -> {
            try {
                var p = new ProcessBuilder("tmux", "resize-pane", "-t", session.name(),
                        "-x", String.valueOf(cols), "-y", String.valueOf(rows))
                        .redirectErrorStream(true).start();
                try (var in = p.getInputStream()) {
                    in.transferTo(java.io.OutputStream.nullOutputStream());
                }
                p.waitFor();
                return Response.noContent().build();
            } catch (IOException | InterruptedException e) {
                LOG.errorf("Failed to resize session '%s': %s", session.name(), e.getMessage());
                return Response.serverError().build();
            }
        }).orElse(Response.status(404).build());
    }

    @POST
    @Path("/{id}/open-terminal")
    public Response openTerminal(@PathParam("id") String id) {
        return registry.find(id).map(session -> {
            var adapter = terminalFactory.resolve();
            if (adapter.isEmpty()) {
                return Response.status(503)
                        .entity("{\"error\":\"No terminal adapter available on this machine\"}")
                        .build();
            }
            try {
                adapter.get().openSession(session.name());
                return Response.ok("{\"opened\":true,\"adapter\":\"" + adapter.get().name() + "\"}").build();
            } catch (IOException | InterruptedException e) {
                LOG.errorf("Failed to open session '%s' in terminal: %s", session.name(), e.getMessage());
                return Response.serverError().build();
            }
        }).orElse(Response.status(404).build());
    }

    @GET
    @Path("/{id}/git-status")
    public Response gitStatus(@PathParam("id") String id) {
        return registry.find(id).map(session -> {
            var dir = session.workingDir();
            if ("unknown".equals(dir)) {
                return Response.ok(GitStatusResponse.notGit()).build();
            }
            try {
                // Step 1: is it a git repo?
                if (!run("git", "-C", dir, "rev-parse", "--git-dir").success()) {
                    return Response.ok(GitStatusResponse.notGit()).build();
                }
                // Step 2: get current branch
                var branch = run("git", "-C", dir, "branch", "--show-current").stdout().trim();

                // Step 3: get GitHub remote
                var remoteUrl = run("git", "-C", dir, "remote", "get-url", "origin").stdout().trim();
                var githubRepo = parseGitHubRepo(remoteUrl);
                if (githubRepo == null) {
                    return Response.ok(GitStatusResponse.noGitHub(branch)).build();
                }

                // Step 4: get PR info via gh
                var ghResult = run("gh", "pr", "view",
                        "--repo", githubRepo,
                        "--json", "number,title,url,state,statusCheckRollup");
                if (!ghResult.success()) {
                    // no PR for this branch, or gh not available
                    if (ghResult.stdout().contains("no pull requests found") ||
                            ghResult.stderr().contains("no pull requests found")) {
                        return Response.ok(GitStatusResponse.noPr(githubRepo, branch)).build();
                    }
                    var errMsg = ghResult.stderr().isBlank() ? "gh not available or not authenticated" : ghResult.stderr().trim();
                    return Response.ok(GitStatusResponse.error(githubRepo, branch, errMsg)).build();
                }

                var pr = parsePrInfo(ghResult.stdout());
                return Response.ok(GitStatusResponse.withPr(githubRepo, branch, pr)).build();

            } catch (IOException | InterruptedException e) {
                LOG.errorf("Failed to get git status for session '%s': %s", session.name(), e.getMessage());
                return Response.serverError().build();
            }
        }).orElse(Response.status(404).build());
    }

    private static final List<Integer> DEFAULT_PORTS =
            List.of(3000, 3001, 4000, 4200, 5000, 5173, 8000, 8080, 8081, 8888);

    @GET
    @Path("/{id}/service-health")
    public Response serviceHealth(@PathParam("id") String id) {
        if (registry.find(id).isEmpty()) return Response.status(404).build();
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
            return Response.ok(results).build();
        } catch (Exception e) {
            LOG.errorf("Failed to check service health: %s", e.getMessage());
            return Response.serverError().build();
        }
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

    private String resolvedPolicy(Session session) {
        return policyRegistry.resolve(session.expiryPolicy().orElse(null)).name();
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
        // https://github.com/owner/repo.git  or  git@github.com:owner/repo.git
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

    private GitStatusResponse.PrInfo parsePrInfo(String json) throws IOException {
        var mapper = new ObjectMapper();
        var node = mapper.readTree(json);
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
                var status = check.path("status").asText("");
                if ("SUCCESS".equalsIgnoreCase(conclusion)) passed++;
                else if ("FAILURE".equalsIgnoreCase(conclusion) || "ERROR".equalsIgnoreCase(conclusion)) failed++;
                else pending++;
            }
        }
        return new GitStatusResponse.PrInfo(number, title, url, state, total, passed, failed, pending);
    }
}
