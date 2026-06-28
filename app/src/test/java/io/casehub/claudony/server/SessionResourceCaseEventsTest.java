package io.casehub.claudony.server;

import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.platform.api.identity.TenancyConstants;

@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class SessionResourceCaseEventsTest {

    @Inject
           SessionRegistry      registry;
    @Inject CaseEventBroadcaster broadcaster;

    @AfterEach
    void cleanup() {
        registry.all().stream().map(Session::id).toList().forEach(registry::remove);
    }

    private Session caseSession(String id, String caseId) {
        return new Session(id, "name-" + id, "/tmp", "cmd", SessionStatus.IDLE,
                Instant.now(), Instant.now(), Optional.empty(),
                Optional.of(caseId), Optional.of("agent"), TenancyConstants.DEFAULT_TENANT_ID);
    }

    private Session standaloneSession(String id) {
        return new Session(id, "name-" + id, "/tmp", "cmd", SessionStatus.IDLE,
                Instant.now(), Instant.now(), Optional.empty(),
                Optional.empty(), Optional.empty(), TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void caseEvents_404_forUnknownSession() {
        given()
            .get("/api/sessions/no-such-session/case-events")
        .then()
            .statusCode(404);
    }

    @Test
    void caseEvents_404_forStandaloneSession() {
        registry.register(standaloneSession("standalone-1"));
        given()
            .get("/api/sessions/standalone-1/case-events")
        .then()
            .statusCode(404);
    }

    @Test
    void caseEvents_contentType_isEventStream() throws Exception {
        registry.register(caseSession("sse-ct-1", "ct-case-1"));
        // SSE streams never close naturally. Use raw HttpURLConnection to read
        // just the response status and Content-Type header without blocking on body.
        // @TestSecurity is applied by Quarkus at the application layer, so raw
        // HTTP requests to the test server get the test identity automatically.
        int port = io.restassured.RestAssured.port;
        var conn = (java.net.HttpURLConnection)
            new java.net.URL("http://localhost:" + port + "/api/sessions/sse-ct-1/case-events").openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(500); // short read timeout — we only need headers
        try {
            int status = conn.getResponseCode(); // reads status line + headers
            String contentType = conn.getHeaderField("Content-Type");
            assertThat(status).isEqualTo(200);
            assertThat(contentType).contains("text/event-stream");
        } finally {
            conn.disconnect();
        }
    }

    @Test
    void caseEvents_emitsInitialState_onConnect() throws Exception {
        registry.register(caseSession("sse-init-1", "init-case-1"));

        var received = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(1);

        broadcaster.subscribe("init-case-1",
                () -> "data: [{\"id\":\"sse-init-1\"}]\n\n")
            .subscribe().with(e -> { received.add(e); latch.countDown(); });

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get(0)).startsWith("data:");
    }

    @Test
    void caseEvents_emitsUpdate_whenBroadcasterFires() throws Exception {
        registry.register(caseSession("sse-upd-1", "upd-case-1"));

        var received = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(2); // initial + emitted

        broadcaster.subscribe("upd-case-1", () -> "data: snap\n\n")
            .subscribe().with(e -> { received.add(e); latch.countDown(); });

        broadcaster.emit("upd-case-1");

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(2);
        assertThat(received).allMatch(e -> e.startsWith("data:"));
    }

    @Test
    void caseEvents_multipleClients_bothReceiveUpdate() throws Exception {
        registry.register(caseSession("sse-multi-1", "multi-case-1"));

        var received1 = new CopyOnWriteArrayList<String>();
        var received2 = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(4); // 2 initials + 2 updates

        broadcaster.subscribe("multi-case-1", () -> "data: m\n\n")
            .subscribe().with(e -> { received1.add(e); latch.countDown(); });
        broadcaster.subscribe("multi-case-1", () -> "data: m\n\n")
            .subscribe().with(e -> { received2.add(e); latch.countDown(); });

        broadcaster.emit("multi-case-1");

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received1).hasSize(2);
        assertThat(received2).hasSize(2);
    }

    @Test
    void strategyConfig_eventsOnly_isActiveInTestProfile() {
        assertThat(broadcaster.strategyType()).isEqualTo("events-only");
    }
}
