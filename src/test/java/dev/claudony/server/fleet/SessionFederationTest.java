package dev.claudony.server.fleet;

import dev.claudony.server.model.SessionResponse;
import dev.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class SessionFederationTest {

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
    void localOnly_excludesPeerSessions() {
        // With ?local=true, only local sessions returned — no fleet fields
        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .queryParam("local", "true")
            .when().get("/api/sessions")
            .then().statusCode(200)
            // All returned sessions have null instanceUrl (local sessions)
            .body("instanceUrl", everyItem(nullValue()));
    }

    @Test
    void federated_includesStaleCachedSessionsFromDownPeer() {
        // Add a peer that can't be reached
        registry.addPeer("dead-peer-id", "http://unreachable-peer:7777",
                "Dead Peer", DiscoverySource.MANUAL, TerminalMode.DIRECT);

        // Pre-populate its session cache (simulates data from before it went down)
        var fakeSession = new SessionResponse(
                "fake-session-id", "claudony-fake", "/tmp", "claude",
                SessionStatus.IDLE,
                Instant.now(), Instant.now(),
                "ws://unreachable-peer:7777/ws/fake-session-id",
                "http://unreachable-peer:7777/app/session/fake-session-id",
                null, null, null);
        registry.updateCachedSessions("dead-peer-id", List.of(fakeSession));

        // Open the circuit (simulate 3 consecutive failures)
        registry.recordFailure("dead-peer-id");
        registry.recordFailure("dead-peer-id");
        registry.recordFailure("dead-peer-id");

        // Federated call should include the stale session
        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .when().get("/api/sessions")
            .then().statusCode(200)
            .body("find { it.id == 'fake-session-id' }.stale", equalTo(true))
            .body("find { it.id == 'fake-session-id' }.instanceName", equalTo("Dead Peer"))
            .body("find { it.id == 'fake-session-id' }.instanceUrl", equalTo("http://unreachable-peer:7777"));
    }

    @Test
    void localOnly_true_neverCallsPeers() {
        // Even with a peer registered, ?local=true must not include any sessions with instanceUrl set
        registry.addPeer("another-peer", "http://another-peer:7777",
                "Another Peer", DiscoverySource.MANUAL, TerminalMode.DIRECT);

        given()
            .header("X-Api-Key", "test-api-key-do-not-use-in-prod")
            .queryParam("local", "true")
            .when().get("/api/sessions")
            .then().statusCode(200)
            .body("instanceUrl", everyItem(nullValue()));
    }
}
