package io.casehub.claudony.server;

import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.platform.api.identity.TenancyConstants;

@QuarkusTest
@TestProfile(RegistryHooksStrategyIntegrationTest.RegistryHooksProfile.class)
class RegistryHooksStrategyIntegrationTest {

    public static class RegistryHooksProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("claudony.case-worker-update", "registry-hooks");
        }
    }

    @Inject SessionRegistry registry;
    @Inject CaseEventBroadcaster broadcaster;

    @AfterEach
    void cleanup() {
        registry.all().stream().map(Session::id).toList().forEach(registry::remove);
    }

    private Session caseSession(String id, String caseId) {
        return new Session(id, "name-" + id, "/tmp", "cmd", SessionStatus.IDLE,
                Instant.now(), Instant.now(), Optional.empty(),
                Optional.of(caseId), Optional.of("worker"), TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void selectedStrategy_isRegistryHooks() {
        assertThat(broadcaster.strategyType()).isEqualTo("registry-hooks");
    }

    @Test
    void register_doesNotTriggerSSEPush() throws Exception {
        var received = new CopyOnWriteArrayList<String>();

        broadcaster.subscribe("rh-case-3", () -> "data: snap\n\n")
                .subscribe().with(received::add);

        registry.register(caseSession("rh-s3", "rh-case-3"));

        Thread.sleep(100);
        assertThat(received).containsExactly("data: snap\n\n"); // only the initial snapshot — register() does not notify
    }

    @Test
    void updateStatus_triggersSSEPush() throws Exception {
        registry.register(caseSession("rh-s1", "rh-case-1"));

        var received = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(2);
        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);

        broadcaster.subscribe("rh-case-1",
                        () -> callCount.incrementAndGet() == 1 ? "data: initial\n\n" : "data: updated\n\n")
                .subscribe().with(e -> { received.add(e); latch.countDown(); });

        registry.updateStatus("rh-s1", SessionStatus.ACTIVE);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get(0)).isEqualTo("data: initial\n\n");
        assertThat(received.get(1)).isEqualTo("data: updated\n\n");
    }

    @Test
    void remove_triggersSSEPush() throws Exception {
        registry.register(caseSession("rh-s2", "rh-case-2"));

        var received = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(2);
        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);

        broadcaster.subscribe("rh-case-2",
                        () -> callCount.incrementAndGet() == 1 ? "data: initial\n\n" : "data: updated\n\n")
                .subscribe().with(e -> { received.add(e); latch.countDown(); });

        registry.remove("rh-s2");

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get(0)).isEqualTo("data: initial\n\n");
        assertThat(received.get(1)).isEqualTo("data: updated\n\n");
    }
}
