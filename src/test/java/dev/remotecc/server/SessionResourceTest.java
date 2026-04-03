package dev.remotecc.server;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;
import jakarta.inject.Inject;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
    @Order(1)
    void listSessionsReturnsEmptyArray() {
        given().when().get("/api/sessions")
            .then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    @Order(2)
    void createSessionReturns201WithSessionId() {
        given().contentType("application/json")
            .body("{\"name\":\"test-api\",\"workingDir\":\"/tmp\",\"command\":\"echo hello\"}")
            .when().post("/api/sessions")
            .then().statusCode(201)
            .body("id", notNullValue())
            .body("name", equalTo("remotecc-test-api"))
            .body("status", equalTo("IDLE"));
    }

    @Test
    @Order(3)
    void getSessionById() {
        var id = given().contentType("application/json")
            .body("{\"name\":\"test-get\",\"workingDir\":\"/tmp\",\"command\":\"echo hi\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().path("id");

        given().when().get("/api/sessions/" + id)
            .then().statusCode(200).body("id", equalTo(id));
    }

    @Test
    @Order(4)
    void deleteSessionReturns204() {
        var id = given().contentType("application/json")
            .body("{\"name\":\"test-del\",\"workingDir\":\"/tmp\",\"command\":\"echo bye\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().path("id");

        given().when().delete("/api/sessions/" + id).then().statusCode(204);
        given().when().get("/api/sessions/" + id).then().statusCode(404);
    }

    @Test
    @Order(5)
    void getUnknownSessionReturns404() {
        given().when().get("/api/sessions/does-not-exist").then().statusCode(404);
    }
}
