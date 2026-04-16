package dev.claudony.server;

import dev.claudony.Await;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class SessionInputOutputTest {

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
    void sendInputToSessionReturns204() {
        var sessionId = given().contentType("application/json")
            .body("{\"name\":\"test-io-1\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().<String>path("id");

        awaitSessionReady(sessionId);

        given().contentType("application/json")
            .body("{\"text\":\"echo input-test-marker\\n\"}")
            .when().post("/api/sessions/" + sessionId + "/input")
            .then().statusCode(204);
    }

    @Test
    void getOutputFromSessionReturnsText() {
        var sessionId = given().contentType("application/json")
            .body("{\"name\":\"test-io-2\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().<String>path("id");

        awaitSessionReady(sessionId);

        given().contentType("application/json")
            .body("{\"text\":\"echo input-test-marker\\n\"}")
            .when().post("/api/sessions/" + sessionId + "/input")
            .then().statusCode(204);

        Await.until(() -> {
            var body = given().when()
                .get("/api/sessions/" + sessionId + "/output?lines=20")
                .then().statusCode(200).extract().asString();
            return body.contains("input-test-marker");
        }, "'input-test-marker' to appear in session output");
    }

    @Test
    void sendInputToUnknownSessionReturns404() {
        given().contentType("application/json")
            .body("{\"text\":\"echo hi\\n\"}")
            .when().post("/api/sessions/does-not-exist/input")
            .then().statusCode(404);
    }

    @Test
    void getOutputFromUnknownSessionReturns404() {
        given().when().get("/api/sessions/does-not-exist/output")
            .then().statusCode(404);
    }

    @Test
    void sendInputWithTmuxKeyNameViaRestPreservesLiteralText() {
        var sessionId = given().contentType("application/json")
            .body("{\"name\":\"test-io-keyname\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().<String>path("id");

        awaitSessionReady(sessionId);

        given().contentType("application/json")
            .body("{\"text\":\"Escape\"}")
            .when().post("/api/sessions/" + sessionId + "/input")
            .then().statusCode(204);

        Await.until(() -> {
            var body = given().when()
                .get("/api/sessions/" + sessionId + "/output?lines=20")
                .then().statusCode(200).extract().asString();
            return body.contains("Escape");
        }, "literal 'Escape' to appear in session output");
    }

    /** Polls until the session has a non-blank prompt (bash is ready). */
    private static void awaitSessionReady(String sessionId) {
        Await.until(() -> {
            var response = given().when()
                .get("/api/sessions/" + sessionId + "/output?lines=5")
                .then().extract().response();
            return response.statusCode() == 200 && !response.asString().isBlank();
        }, "session bash prompt to appear");
    }
}
