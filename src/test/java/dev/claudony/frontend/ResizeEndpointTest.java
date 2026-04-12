package dev.claudony.frontend;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.*;
import jakarta.inject.Inject;
import dev.claudony.server.SessionRegistry;
import dev.claudony.server.TmuxService;
import static io.restassured.RestAssured.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResizeEndpointTest {

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
    void resizeSessionReturns204() throws Exception {
        var sessionId = given().contentType("application/json")
            .body("{\"name\":\"resize-test-1\",\"workingDir\":\"/tmp\",\"command\":\"bash\"}")
            .when().post("/api/sessions")
            .then().statusCode(201).extract().path("id");
        Thread.sleep(200);

        given().contentType("application/json").when()
            .post("/api/sessions/" + sessionId + "/resize?cols=120&rows=40")
            .then().statusCode(204);
    }

    @Test
    @Order(2)
    void resizeUnknownSessionReturns404() {
        given().contentType("application/json").when()
            .post("/api/sessions/does-not-exist/resize?cols=80&rows=24")
            .then().statusCode(404);
    }
}
