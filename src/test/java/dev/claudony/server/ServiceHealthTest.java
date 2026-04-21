package dev.claudony.server;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class ServiceHealthTest {

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
    void serviceHealthReturns404ForUnknownSession() {
        given().when().get("/api/sessions/nonexistent/service-health")
            .then().statusCode(404);
    }

    @Test
    void serviceHealthReturnsArrayForKnownSession() {
        // Register a session directly — no real tmux needed for this endpoint
        var session = new dev.claudony.server.model.Session(
            "test-svc-id", "claudony-test-svc", "/tmp",
            "bash", dev.claudony.server.model.SessionStatus.IDLE,
            java.time.Instant.now(), java.time.Instant.now(), java.util.Optional.empty());
        registry.register(session);

        // Returns a JSON array (may be empty if no services are running on test ports)
        given().when().get("/api/sessions/test-svc-id/service-health")
            .then()
            .statusCode(200)
            .body("$", isA(java.util.List.class));
    }

    @Test
    void serviceHealthReturnsOnlyUpPorts() {
        // The Quarkus test server itself runs on port 8081 — it will always be up
        var session = new dev.claudony.server.model.Session(
            "test-svc-up-id", "claudony-test-svc-up", "/tmp",
            "bash", dev.claudony.server.model.SessionStatus.IDLE,
            java.time.Instant.now(), java.time.Instant.now(), java.util.Optional.empty());
        registry.register(session);

        // All returned entries must have up=true
        given().when().get("/api/sessions/test-svc-up-id/service-health")
            .then()
            .statusCode(200)
            .body("findAll { it.up == false }.size()", equalTo(0));
    }
}
