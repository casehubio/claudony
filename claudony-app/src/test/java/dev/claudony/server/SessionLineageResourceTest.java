package dev.claudony.server;

import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class SessionLineageResourceTest {

    @Inject SessionRegistry registry;

    @AfterEach
    void cleanup() {
        registry.all().stream().map(Session::id).toList().forEach(registry::remove);
    }

    // ── Happy path: session with caseId → 200 empty list (EmptyCaseLineageQuery is active)

    @Test
    void sessionWithCaseId_returns200WithList() {
        var now = Instant.now();
        registry.register(new Session("lin-s1", "claudony-lin-s1", "/tmp", "claude",
                SessionStatus.IDLE, now, now, Optional.empty(),
                Optional.of("550e8400-e29b-41d4-a716-446655440000"), Optional.of("researcher")));

        given().when().get("/api/sessions/lin-s1/lineage")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));          // EmptyCaseLineageQuery returns []
    }

    // ── No caseId: session without caseId → 200 empty list (early exit, lineageQuery not called)

    @Test
    void sessionWithoutCaseId_returns200EmptyList() {
        var now = Instant.now();
        registry.register(new Session("lin-s2", "claudony-lin-s2", "/tmp", "claude",
                SessionStatus.IDLE, now, now, Optional.empty(),
                Optional.empty(), Optional.empty()));

        given().when().get("/api/sessions/lin-s2/lineage")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    // ── 404: unknown session id

    @Test
    void unknownSession_returns404() {
        given().when().get("/api/sessions/does-not-exist/lineage")
                .then()
                .statusCode(404);
    }

    // ── Robustness: non-UUID caseId stored in session → 200 empty list, not 500

    @Test
    void nonUuidCaseId_returns200EmptyListNotError() {
        var now = Instant.now();
        registry.register(new Session("lin-s3", "claudony-lin-s3", "/tmp", "claude",
                SessionStatus.IDLE, now, now, Optional.empty(),
                Optional.of("not-a-uuid"), Optional.of("analyst")));

        given().when().get("/api/sessions/lin-s3/lineage")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }
}
