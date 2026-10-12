package io.casehub.claudony.server.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class ClaudonyRegistryApiTest {

    private static final String BASE = "/api/claudony/registry";

    @Test
    void registerAndResolve() {
        var id = "test-svc-" + System.nanoTime();
        registerEntry(id, "service", "default");

        RestAssured.given()
                .when().get(BASE + "/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("type", is("service"))
                .body("namespace", is("default"))
                .body("health", is("HEALTHY"));
    }

    @Test
    void resolve_notFound() {
        RestAssured.given()
                .when().get(BASE + "/nonexistent-" + System.nanoTime())
                .then().statusCode(404);
    }

    @Test
    void heartbeat() {
        var id = "hb-svc-" + System.nanoTime();
        registerEntry(id, "service", "default");

        RestAssured.given()
                .contentType(ContentType.JSON)
                .when().post(BASE + "/" + id + "/heartbeat")
                .then().statusCode(204);

        RestAssured.given()
                .when().get(BASE + "/" + id)
                .then().statusCode(200)
                .body("health", is("HEALTHY"));
    }

    @Test
    void deregister() {
        var id = "dereg-svc-" + System.nanoTime();
        registerEntry(id, "service", "default");

        RestAssured.given()
                .contentType(ContentType.JSON)
                .when().post(BASE + "/" + id + "/deregister")
                .then().statusCode(200)
                .body("deregistered.id", is(id));

        RestAssured.given()
                .when().get(BASE + "/" + id)
                .then().statusCode(404);
    }

    @Test
    void discover() {
        var id = "disc-svc-" + System.nanoTime();
        registerEntry(id, "worker", "test-ns");

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"tenancyId\": \"default\", \"type\": \"worker\", \"namespace\": \"test-ns\"}")
                .when().post(BASE + "/discover")
                .then().statusCode(200)
                .body("findAll { it.id == '%s' }.size()".formatted(id), is(1));
    }

    @Test
    void linkAndRelationships() {
        var src = "link-src-" + System.nanoTime();
        var tgt = "link-tgt-" + System.nanoTime();
        registerEntry(src, "pool", "default");
        registerEntry(tgt, "session", "default");

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"sourceId\": \"%s\", \"targetId\": \"%s\", \"type\": \"owns\"}".formatted(src, tgt))
                .when().post(BASE + "/link")
                .then().statusCode(204);

        RestAssured.given()
                .when().get(BASE + "/" + src + "/relationships")
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].sourceId", is(src))
                .body("[0].targetId", is(tgt))
                .body("[0].type", is("owns"));
    }

    @Test
    void unlink() {
        var src = "unlink-src-" + System.nanoTime();
        var tgt = "unlink-tgt-" + System.nanoTime();
        registerEntry(src, "pool", "default");
        registerEntry(tgt, "session", "default");

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"sourceId\": \"%s\", \"targetId\": \"%s\", \"type\": \"owns\"}".formatted(src, tgt))
                .when().post(BASE + "/link")
                .then().statusCode(204);

        RestAssured.given()
                .contentType(ContentType.JSON)
                .when().post(BASE + "/" + src + "/unlink/" + tgt)
                .then().statusCode(204);

        RestAssured.given()
                .when().get(BASE + "/" + src + "/relationships")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void relationships_empty() {
        var id = "rel-empty-" + System.nanoTime();
        registerEntry(id, "service", "default");

        RestAssured.given()
                .when().get(BASE + "/" + id + "/relationships")
                .then().statusCode(200)
                .body("$", instanceOf(java.util.List.class))
                .body("size()", is(0));
    }

    private void registerEntry(String id, String type, String namespace) {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"id\": \"%s\", \"type\": \"%s\", \"namespace\": \"%s\", \"tenancyId\": \"default\", \"metadata\": {}, \"registeredAt\": \"2026-10-12T00:00:00Z\", \"ttl\": \"PT5M\", \"health\": \"HEALTHY\"}".formatted(id, type, namespace))
                .when().post(BASE)
                .then().statusCode(204);
    }
}
