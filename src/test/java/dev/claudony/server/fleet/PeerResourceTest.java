package dev.claudony.server.fleet;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class PeerResourceTest {

    @Inject PeerRegistry registry;

    @AfterEach
    void cleanup() {
        registry.getAllPeers().stream()
                .filter(p -> p.source() == DiscoverySource.MANUAL)
                .map(PeerRecord::id)
                .toList()
                .forEach(registry::removePeer);
    }

    @Test
    void listPeers_emptyInitially() {
        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .when().get("/api/peers")
            .then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void addPeer_appearsInList() {
        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .contentType(ContentType.JSON)
            .body("{\"url\":\"http://test-peer:7777\",\"name\":\"Test Peer\",\"terminalMode\":\"DIRECT\"}")
            .when().post("/api/peers")
            .then().statusCode(201)
            .body("url", equalTo("http://test-peer:7777"))
            .body("name", equalTo("Test Peer"))
            .body("source", equalTo("MANUAL"));

        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .when().get("/api/peers")
            .then().statusCode(200).body("$", hasSize(1));
    }

    @Test
    void deletePeer_removesFromList() {
        var id = given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .contentType(ContentType.JSON)
            .body("{\"url\":\"http://delete-peer:7777\",\"name\":\"Delete Me\"}")
            .when().post("/api/peers")
            .then().statusCode(201).extract().path("id");

        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .when().delete("/api/peers/" + id)
            .then().statusCode(204);

        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .when().get("/api/peers")
            .then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void patchPeer_updatesName() {
        var id = given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .contentType(ContentType.JSON)
            .body("{\"url\":\"http://patch-peer:7777\",\"name\":\"Old Name\"}")
            .when().post("/api/peers")
            .then().statusCode(201).extract().path("id");

        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .contentType(ContentType.JSON)
            .body("{\"name\":\"New Name\"}")
            .when().patch("/api/peers/" + id)
            .then().statusCode(200)
            .body("name", equalTo("New Name"));
    }

    @Test
    void deleteUnknownPeer_returns404() {
        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .when().delete("/api/peers/does-not-exist")
            .then().statusCode(404);
    }

    @Test
    void unauthenticated_returns401() {
        given()
            .when().get("/api/peers")
            .then().statusCode(401);
    }

    @Test
    void generateFleetKey_returnsNonBlankKey() {
        var key = given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .when().post("/api/peers/generate-fleet-key")
            .then().statusCode(200)
            .extract().asString();
        org.assertj.core.api.Assertions.assertThat(key).isNotBlank();
    }
}
