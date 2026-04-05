package dev.remotecc.server;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.*;
import jakarta.inject.Inject;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
    @Order(1)
    void sendInputToSessionReturns204() throws Exception {
        var sessionId = given().contentType("application/json")
            .body("{\"name\":\"test-io-1\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().path("id");
        Thread.sleep(300);

        given().contentType("application/json")
            .body("{\"text\":\"echo input-test-marker\\n\"}")
            .when().post("/api/sessions/" + sessionId + "/input")
            .then().statusCode(204);
    }

    @Test
    @Order(2)
    void getOutputFromSessionReturnsText() throws Exception {
        var sessionId = given().contentType("application/json")
            .body("{\"name\":\"test-io-2\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().path("id");
        Thread.sleep(300);

        given().contentType("application/json")
            .body("{\"text\":\"echo input-test-marker\\n\"}")
            .when().post("/api/sessions/" + sessionId + "/input")
            .then().statusCode(204);

        Thread.sleep(300);
        given().when().get("/api/sessions/" + sessionId + "/output?lines=20")
            .then()
            .statusCode(200)
            .contentType("text/plain")
            .body(containsString("input-test-marker"));
    }

    @Test
    @Order(3)
    void sendInputToUnknownSessionReturns404() {
        given().contentType("application/json")
            .body("{\"text\":\"echo hi\\n\"}")
            .when().post("/api/sessions/does-not-exist/input")
            .then().statusCode(404);
    }

    @Test
    @Order(4)
    void getOutputFromUnknownSessionReturns404() {
        given().when().get("/api/sessions/does-not-exist/output")
            .then().statusCode(404);
    }

    @Test
    @Order(5)
    void sendInputWithTmuxKeyNameViaRestPreservesLiteralText() throws Exception {
        var sessionId = given().contentType("application/json")
            .body("{\"name\":\"test-io-keyname\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().path("id");
        Thread.sleep(300);

        given().contentType("application/json")
            .body("{\"text\":\"Escape\"}")
            .when().post("/api/sessions/" + sessionId + "/input")
            .then().statusCode(204);

        Thread.sleep(300);
        given().when().get("/api/sessions/" + sessionId + "/output?lines=20")
            .then()
            .statusCode(200)
            .body(containsString("Escape"));
    }
}
