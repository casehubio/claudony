package io.casehub.claudony.server.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class ClaudonyAppApiTest {

    @Test
    void listApps_empty() {
        RestAssured.given()
                .when().get("/api/claudony/apps")
                .then().statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    void registerApp_success() {
        var name = "test-app-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        var body = """
                {
                    "name": "%s",
                    "displayName": "Test App",
                    "description": "A test application",
                    "pools": [
                        {"agentId": "reviewer", "minActive": 0, "maxActive": 3}
                    ]
                }
                """.formatted(name);

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/api/claudony/apps")
                .then().statusCode(200)
                .body("name", is(name))
                .body("displayName", is("Test App"))
                .body("health", is("HEALTHY"))
                .body("pools.size()", is(1));
    }

    @Test
    void getApp_notFound() {
        RestAssured.given()
                .when().get("/api/claudony/apps/nonexistent-app")
                .then().statusCode(404);
    }

    @Test
    void heartbeat_success() {
        var name = "hb-test-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name": "%s", "displayName": "HB Test", "description": "", "pools": []}
                        """.formatted(name))
                .when().post("/api/claudony/apps")
                .then().statusCode(200);

        RestAssured.given()
                .contentType(ContentType.JSON)
                .when().post("/api/claudony/apps/" + name + "/heartbeat")
                .then().statusCode(200);
    }
}
