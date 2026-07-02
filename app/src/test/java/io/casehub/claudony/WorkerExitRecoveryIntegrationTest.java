package io.casehub.claudony;

import io.casehub.claudony.casehub.ClaudonyReactiveWorkerProvisioner;
import io.casehub.claudony.casehub.ClaudonyWorkerExecutionManager;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.engine.common.spi.scheduler.WorkerBackend;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import io.casehub.platform.api.identity.TenancyConstants;

/**
 * Integration test for the recovery path: server restart with in-flight casehub sessions.
 *
 * Verifies that ClaudonyWorkerExecutionManager.watch() — called directly by bootstrapCasehubWatchers()
 * after reading session state from the registry — detects session exit and publishes
 * WorkflowExecutionCompleted to the event bus with the correct fields.
 *
 * Uses the default test profile (no CasehubEnabledProfile) so WorkflowExecutionCompletedHandler
 * is NOT registered, and the test consumer is the sole recipient of the send() event.
 * CDI-only — no HTTP endpoints, no @TestSecurity.
 */
@QuarkusTest
class WorkerExitRecoveryIntegrationTest {

    @Inject SessionRegistry registry;
    @Inject @WorkerBackend ClaudonyWorkerExecutionManager execManager;
    @Inject EventBus eventBus;

    @InjectMock TmuxService tmuxService;

    private String seededSessionId;

    @AfterEach
    void cleanup() {
        // Shutdown any lingering watchers first, then remove from registry.
        // Without shutdown(), a slow watcher thread might race with registry.remove() here.
        execManager.shutdown();
        if (seededSessionId != null) {
            registry.remove(seededSessionId);
            seededSessionId = null;
        }
    }

    @Test
    void bootstrapWatchers_startsWatcherForCasehubSession_andPublishesCompletionWhenSessionGone()
            throws Exception {
        var caseId = UUID.randomUUID();
        var roleName = "agent";
        seededSessionId = UUID.randomUUID().toString();
        var sessionName = ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX + seededSessionId;

        // Simulate what bootstrapRegistry() produces for a recovered casehub session
        registry.register(new Session(seededSessionId, sessionName, "unknown", "claude",
                SessionStatus.IDLE, Instant.now(), Instant.now(),
                Optional.empty(), Optional.of(caseId.toString()), Optional.of(roleName),
                TenancyConstants.DEFAULT_TENANT_ID));

        var instance = new CaseInstance();
        instance.setUuid(caseId);

        // Register test consumer for the completion event.
        // In default profile, WorkflowExecutionCompletedHandler is NOT registered
        // (engine not indexed), so this test consumer is the sole recipient.
        AtomicReference<WorkflowExecutionCompleted> captured = new AtomicReference<>();
        eventBus.consumer(EventBusAddresses.WORKER_EXECUTION_FINISHED,
                msg -> captured.set((WorkflowExecutionCompleted) msg.body()));

        // Stub sessionExists() to return false immediately — watcher detects exit
        when(tmuxService.sessionExists(sessionName)).thenReturn(false);

        // Call watch() directly — this is what bootstrapCasehubWatchers() does
        execManager.watch(seededSessionId, sessionName, instance,
                io.casehub.worker.api.Worker.builder().name(roleName).capabilityName(roleName).function(new io.casehub.worker.api.WorkerFunction.Sync(ctx -> io.casehub.worker.api.WorkerResult.of(java.util.Map.of()))).build());

        // Wait for the watcher virtual thread to detect exit and publish
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(captured.get()).isNotNull());

        var event = captured.get();
        assertThat(event.caseInstance().getUuid()).isEqualTo(caseId);
        assertThat(event.worker().name()).isEqualTo(roleName);
        assertThat(event.idempotency())
                .isEqualTo(caseId + ":" + roleName + ":" + seededSessionId);
    }
}
