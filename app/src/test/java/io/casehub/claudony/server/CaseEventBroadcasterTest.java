package io.casehub.claudony.server;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for CaseEventBroadcaster. Runs with %test profile which sets
 * claudony.case-worker-update=events-only to avoid background heartbeat interference.
 */
@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class CaseEventBroadcasterTest {

    @Inject
    CaseEventBroadcaster broadcaster;

    @Test
    void selectedStrategy_isEventsOnly_inTestProfile() {
        assertThat(broadcaster.strategyType()).isEqualTo("events-only");
    }

    @Test
    void subscribe_emitsInitialSnapshot() {
        var received = new CopyOnWriteArrayList<String>();

        broadcaster.subscribe("bc-case-1", () -> "data: init\n\n")
                .subscribe().with(received::add);

        assertThat(received).containsExactly("data: init\n\n");
    }

    @Test
    void emit_pushesSnapshotToSubscribers() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(2); // initial + emitted

        broadcaster.subscribe("bc-case-2", () -> "data: snap\n\n")
                .subscribe().with(e -> { received.add(e); latch.countDown(); });

        broadcaster.emit("bc-case-2");

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(2);
    }

    @Test
    void emit_nullCaseId_isNoOp() {
        assertThatCode(() -> broadcaster.emit(null)).doesNotThrowAnyException();
    }

    @Test
    void emit_unknownCaseId_isNoOp() {
        assertThatCode(() -> broadcaster.emit("no-subscribers")).doesNotThrowAnyException();
    }
}
