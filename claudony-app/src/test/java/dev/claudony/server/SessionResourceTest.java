package dev.claudony.server;

import dev.claudony.server.model.Session;
import dev.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import static io.restassured.RestAssured.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class SessionResourceTest {

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
    void listSessionsReturnsEmptyArray() {
        given().when().get("/api/sessions")
            .then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void createSessionReturns201WithSessionId() {
        given().contentType("application/json")
            .body("{\"name\":\"test-api\",\"workingDir\":\"/tmp\",\"command\":\"echo hello\"}")
            .when().post("/api/sessions")
            .then().statusCode(201)
            .body("id", notNullValue())
            .body("name", equalTo("claudony-test-api"))
            .body("status", equalTo("IDLE"));
    }

    @Test
    void getSessionById() {
        var id = given().contentType("application/json")
            .body("{\"name\":\"test-get\",\"workingDir\":\"/tmp\",\"command\":\"echo hi\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().path("id");

        given().when().get("/api/sessions/" + id)
            .then().statusCode(200).body("id", equalTo(id));
    }

    @Test
    void deleteSessionReturns204() {
        var id = given().contentType("application/json")
            .body("{\"name\":\"test-del\",\"workingDir\":\"/tmp\",\"command\":\"echo bye\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().path("id");

        given().when().delete("/api/sessions/" + id).then().statusCode(204);
        given().when().get("/api/sessions/" + id).then().statusCode(404);
    }

    @Test
    void getUnknownSessionReturns404() {
        given().when().get("/api/sessions/does-not-exist").then().statusCode(404);
    }

    @Test
    void renameSessionReturns200WithNewName() throws Exception {
        var id = given().contentType("application/json")
            .body("{\"name\":\"test-rename\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().path("id");

        given().when().patch("/api/sessions/" + id + "/rename?name=renamed")
            .then()
            .statusCode(200)
            .body("name", equalTo("claudony-renamed"));

        // Verify registry reflects the new name
        given().when().get("/api/sessions/" + id)
            .then().statusCode(200).body("name", equalTo("claudony-renamed"));
    }

    @Test
    void createSessionWithDuplicateNameReturns409() {
        given().contentType("application/json")
            .body("{\"name\":\"test-dup\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201);

        given().contentType("application/json")
            .body("{\"name\":\"test-dup\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then()
            .statusCode(409)
            .body("error", containsString("claudony-test-dup"));
    }

    @Test
    void createSessionWithOverwriteReplacesExistingSession() {
        var originalId = given().contentType("application/json")
            .body("{\"name\":\"test-overwrite\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().<String>path("id");

        var newId = given().contentType("application/json")
            .body("{\"name\":\"test-overwrite\",\"workingDir\":\"/home\",\"command\":\"bash\"}")
            .when().post("/api/sessions?overwrite=true")
            .then()
            .statusCode(201)
            .body("name", equalTo("claudony-test-overwrite"))
            .body("workingDir", equalTo("/home"))
            .extract().<String>path("id");

        assertNotEquals(originalId, newId, "Overwrite should produce a new session ID");

        // Exactly one session with this name remains
        given().when().get("/api/sessions")
            .then().statusCode(200)
            .body("findAll { it.name == 'claudony-test-overwrite' }.size()", equalTo(1));
    }

    @Test
    void overwriteWithNonExistentNameCreatesNormally() {
        given().contentType("application/json")
            .body("{\"name\":\"test-new-overwrite\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions?overwrite=true")
            .then()
            .statusCode(201)
            .body("name", equalTo("claudony-test-new-overwrite"));
    }

    @Test
    void resizeSessionReturns204() throws Exception {
        var id = given().contentType("application/json")
            .body("{\"name\":\"test-resize\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().path("id");

        given().contentType("application/json")
            .when().post("/api/sessions/" + id + "/resize?cols=120&rows=40")
            .then().statusCode(204);
    }

    @Test
    void renameSessionToExistingNameReturns409AndDoesNotUpdateRegistry() {
        var idA = given().contentType("application/json")
            .body("{\"name\":\"test-rename-conflict-a\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().<String>path("id");

        var idB = given().contentType("application/json")
            .body("{\"name\":\"test-rename-conflict-b\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().<String>path("id");

        // Try to rename B to A's name — should conflict
        given().when().patch("/api/sessions/" + idB + "/rename?name=test-rename-conflict-a")
            .then()
            .statusCode(409)
            .body("error", containsString("claudony-test-rename-conflict-a"));

        // Registry must still reflect B's original name
        given().when().get("/api/sessions/" + idB)
            .then().statusCode(200).body("name", equalTo("claudony-test-rename-conflict-b"));
    }

    @Test
    @TestSecurity(user = "test", roles = "user")
    void sessionResponseIncludesCaseIdAndRoleName() throws Exception {
        var now = Instant.now();
        var session = new Session("case-session-id", "claudony-test-case", "/tmp", "claude",
                SessionStatus.IDLE, now, now, Optional.empty(),
                Optional.of("test-case-123"), Optional.of("researcher"));
        registry.register(session);

        var response = given().get("/api/sessions/case-session-id").then()
                .statusCode(200).extract().asString();

        assertTrue(response.contains("\"caseId\":\"test-case-123\""), "caseId missing: " + response);
        assertTrue(response.contains("\"roleName\":\"researcher\""), "roleName missing: " + response);

        registry.remove("case-session-id");
    }

    @Test
    @TestSecurity(user = "test", roles = "user")
    void listByCaseId_returnsOnlyMatchingSessions() {
        var now = Instant.now();
        var s1 = new Session("cq-s1", "claudony-worker-1", "/tmp", "claude",
                SessionStatus.ACTIVE, now.minusSeconds(5), now.minusSeconds(5), Optional.empty(),
                Optional.of("case-q"), Optional.of("researcher"));
        var s2 = new Session("cq-s2", "claudony-worker-2", "/tmp", "claude",
                SessionStatus.IDLE, now, now, Optional.empty(),
                Optional.of("case-q"), Optional.of("coder"));
        var s3 = new Session("cq-s3", "claudony-worker-3", "/tmp", "claude",
                SessionStatus.IDLE, now, now, Optional.empty(),
                Optional.of("case-other"), Optional.of("reviewer"));
        registry.register(s1);
        registry.register(s2);
        registry.register(s3);

        var result = given().queryParam("caseId", "case-q")
                .get("/api/sessions").then()
                .statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(result).hasSize(2);

        registry.remove("cq-s1");
        registry.remove("cq-s2");
        registry.remove("cq-s3");
    }

    @Test
    @TestSecurity(user = "test", roles = "user")
    void listByCaseId_returnsEmptyForUnknownCase() {
        var result = given().queryParam("caseId", "no-such-case")
                .get("/api/sessions").then()
                .statusCode(200)
                .extract().jsonPath().getList("$");

        assertThat(result).isEmpty();
    }

}
