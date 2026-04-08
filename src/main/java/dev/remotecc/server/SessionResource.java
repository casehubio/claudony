package dev.remotecc.server;

import dev.remotecc.agent.terminal.TerminalAdapterFactory;
import dev.remotecc.config.RemoteCCConfig;
import dev.remotecc.server.model.*;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

@Path("/api/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class SessionResource {

    private static final Logger LOG = Logger.getLogger(SessionResource.class);

    @Inject RemoteCCConfig config;
    @Inject SessionRegistry registry;
    @Inject TmuxService tmux;
    @Inject TerminalAdapterFactory terminalFactory;

    @GET
    public java.util.List<SessionResponse> list() {
        return registry.all().stream()
                .map(s -> SessionResponse.from(s, config.port()))
                .toList();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        return registry.find(id)
                .map(s -> Response.ok(SessionResponse.from(s, config.port())).build())
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
            } catch (Exception e) {
                LOG.warnf("Could not clean up existing session '%s': %s", name, e.getMessage());
            }
        }

        // Ensure the working directory exists
        try { java.nio.file.Files.createDirectories(java.nio.file.Path.of(workingDir)); }
        catch (Exception ignored) {}
        var now = Instant.now();
        var session = new Session(id, name, workingDir, command, SessionStatus.IDLE, now, now);
        try {
            tmux.createSession(name, workingDir, command);
            registry.register(session);
            LOG.infof("Created session '%s' (id=%s)", name, id);
            return Response.status(201)
                    .entity(SessionResponse.from(session, config.port()))
                    .build();
        } catch (Exception e) {
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
            } catch (Exception e) {
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
                        session.command(), session.status(), session.createdAt(), Instant.now());
                registry.register(renamed);
                return Response.ok(SessionResponse.from(renamed, config.port())).build();
            } catch (Exception e) {
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
                return Response.noContent().build();
            } catch (Exception e) {
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
            } catch (Exception e) {
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
            } catch (Exception e) {
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
            } catch (Exception e) {
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

            } catch (Exception e) {
                LOG.errorf("Failed to get git status for session '%s': %s", session.name(), e.getMessage());
                return Response.serverError().build();
            }
        }).orElse(Response.status(404).build());
    }

    private record RunResult(int exitCode, String stdout, String stderr) {
        boolean success() { return exitCode == 0; }
    }

    private RunResult run(String... cmd) throws Exception {
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

    private GitStatusResponse.PrInfo parsePrInfo(String json) throws Exception {
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
