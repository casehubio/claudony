package dev.claudony.server;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class GitStatusTest {

    @Inject SessionRegistry registry;
    @Inject TmuxService tmux;

    @AfterEach
    void cleanup() throws Exception {
        for (var s : registry.all()) {
            registry.remove(s.id());
            try { tmux.killSession(s.name()); } catch (Exception ignored) {}
        }
    }

    @Test
    void gitStatusReturns404ForUnknownSession() {
        given().when().get("/api/sessions/nonexistent/git-status")
            .then().statusCode(404);
    }

    @Test
    void gitStatusReturnsNotGitForUnknownWorkingDir() {
        // Sessions bootstrapped from tmux have workingDir="unknown"
        // We simulate this by registering a session directly with workingDir=unknown
        var session = new dev.claudony.server.model.Session(
            "test-git-unknown-id", "claudony-test-git-unknown", "unknown",
            "bash", dev.claudony.server.model.SessionStatus.IDLE,
            java.time.Instant.now(), java.time.Instant.now(), java.util.Optional.empty());
        registry.register(session);

        given().when().get("/api/sessions/test-git-unknown-id/git-status")
            .then()
            .statusCode(200)
            .body("gitRepo", equalTo(false));
    }

    @Test
    void gitStatusReturnsNotGitForNonGitDirectory() {
        var session = new dev.claudony.server.model.Session(
            "test-git-nogit-id", "claudony-test-git-nogit", "/tmp",
            "bash", dev.claudony.server.model.SessionStatus.IDLE,
            java.time.Instant.now(), java.time.Instant.now(), java.util.Optional.empty());
        registry.register(session);

        // /tmp is not a git repo
        given().when().get("/api/sessions/test-git-nogit-id/git-status")
            .then()
            .statusCode(200)
            .body("gitRepo", equalTo(false));
    }

    @Test
    void gitStatusDetectsGitRepoAndBranch() {
        // Use this project's own directory — it's a git repo
        var projectDir = System.getProperty("user.dir");
        var session = new dev.claudony.server.model.Session(
            "test-git-repo-id", "claudony-test-git-repo", projectDir,
            "bash", dev.claudony.server.model.SessionStatus.IDLE,
            java.time.Instant.now(), java.time.Instant.now(), java.util.Optional.empty());
        registry.register(session);

        given().when().get("/api/sessions/test-git-repo-id/git-status")
            .then()
            .statusCode(200)
            .body("gitRepo", equalTo(true))
            .body("branch", not(emptyOrNullString()))
            .body("githubRepo", equalTo("mdproctor/claudony"));
    }
}
