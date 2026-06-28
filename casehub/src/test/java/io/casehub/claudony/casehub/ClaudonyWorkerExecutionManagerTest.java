package io.casehub.claudony.casehub;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.casehub.claudony.server.SessionRegistry;
import io.casehub.claudony.server.TmuxService;
import io.casehub.claudony.server.model.Session;
import io.casehub.claudony.server.model.SessionStatus;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.vertx.mutiny.core.eventbus.EventBus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClaudonyWorkerExecutionManagerTest {

    private TmuxService tmuxService;
    private SessionRegistry registry;
    private WorkerSessionMapping sessionMapping;
    private CaseHubConfig config;
    private EventBus eventBus;
    private ClaudonyWorkerExecutionManager manager;

    private static final String SESSION_PREFIX = ClaudonyReactiveWorkerProvisioner.SESSION_PREFIX;

    @BeforeEach
    void setUp() {
        tmuxService = mock(TmuxService.class);
        registry = new SessionRegistry(() -> io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID);
        sessionMapping = new WorkerSessionMapping();
        config = mock(CaseHubConfig.class);
        eventBus = mock(EventBus.class);
        when(config.enabled()).thenReturn(true);
        when(config.workerExitPollMs()).thenReturn(50L);
        when(config.workerExitMaxPollFailures()).thenReturn(3);
        manager = new ClaudonyWorkerExecutionManager(tmuxService, registry, sessionMapping, config, eventBus, null);
    }

    // ── Normal path: session exits naturally ───────────────────────────────────

    @Test
    void submit_publishesCompletionWhenSessionExits() throws Exception {
        var caseId = UUID.randomUUID();
        var sessionId = "abc123";
        var sessionName = SESSION_PREFIX + sessionId;
        seedSession(sessionId, caseId, "agent");

        var instance = caseInstance(caseId);
        var worker = worker("agent");

        // Session alive then gone
        when(tmuxService.sessionExists(sessionName)).thenReturn(true, false);

        manager.submit(null, instance, worker, null, Map.of())
                .await().indefinitely();

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        verify(eventBus).send(
                                eq(EventBusAddresses.WORKER_EXECUTION_FINISHED),
                                any(WorkflowExecutionCompleted.class)));
    }

    @Test
    void submit_completionEventHasDeterministicIdempotencyKey() throws Exception {
        var caseId = UUID.randomUUID();
        var sessionId = "abc123";
        var sessionName = SESSION_PREFIX + sessionId;
        seedSession(sessionId, caseId, "agent");
        when(tmuxService.sessionExists(sessionName)).thenReturn(false);

        var instance = caseInstance(caseId);
        var worker = worker("agent");

        AtomicReference<WorkflowExecutionCompleted> captured = new AtomicReference<>();
        doAnswer(inv -> { captured.set(inv.getArgument(1)); return null; })
                .when(eventBus).send(anyString(), any());

        manager.submit(null, instance, worker, null, Map.of()).await().indefinitely();

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(() -> captured.get() != null);

        String key = captured.get().idempotency();
        assertThat(key).isEqualTo(caseId + ":agent:" + sessionId);
    }

    // ── Termination path: registry removed first ──────────────────────────────

    @Test
    void submit_doesNotPublish_whenRegistryEntryRemovedBeforeSessionDisappears() throws Exception {
        var caseId = UUID.randomUUID();
        var sessionId = "def456";
        var sessionName = SESSION_PREFIX + sessionId;
        seedSession(sessionId, caseId, "analyst");

        // Simulates terminate() winning the atomic gate by removing from registry
        // inside the sessionExists() mock — sequential simulation, not a true concurrent race.
        // Production correctness is guaranteed by ConcurrentHashMap.remove() atomicity.
        // See race_watcherDetectsExit_terminateWinsAtomicGate_noPublish for the latch-based variant.
        when(tmuxService.sessionExists(sessionName)).thenAnswer(inv -> {
            registry.remove(sessionId);
            return false;
        });

        manager.submit(null, caseInstance(caseId), worker("analyst"), null, Map.of())
                .await().indefinitely();

        Thread.sleep(300);
        verify(eventBus, never()).send(anyString(), any());
    }

    @Test
    void race_watcherDetectsExit_terminateWinsAtomicGate_noPublish() throws Exception {
        var caseId = UUID.randomUUID();
        var sessionId = "race789";
        var sessionName = SESSION_PREFIX + sessionId;
        seedSession(sessionId, caseId, "racer");

        // Phase 1: watcher detects session gone, then blocks before calling registry.remove()
        var watcherAtGate = new java.util.concurrent.CountDownLatch(1);
        var terminateDone = new java.util.concurrent.CountDownLatch(1);

        when(tmuxService.sessionExists(sessionName)).thenAnswer(inv -> {
            watcherAtGate.countDown();  // signal: watcher reached the gate
            terminateDone.await();      // wait: let terminate() win registry.remove()
            return false;
        });

        manager.submit(null, caseInstance(caseId), worker("racer"), null, Map.of())
                .await().indefinitely();

        // Phase 2: wait for watcher to be at the gate, then simulate terminate() winning
        watcherAtGate.await();
        registry.remove(sessionId);    // terminate() wins the atomic gate
        terminateDone.countDown();     // release watcher

        Thread.sleep(300);
        // Watcher's registry.remove() returns null — no publish
        verify(eventBus, never()).send(anyString(), any());
    }

    // ── Guard: casehub disabled ────────────────────────────────────────────────

    @Test
    void submit_doesNothing_whenCasehubDisabled() {
        when(config.enabled()).thenReturn(false);

        manager.submit(null, caseInstance(UUID.randomUUID()), worker("agent"), null, Map.of())
                .await().indefinitely();

        verifyNoInteractions(tmuxService, eventBus);
    }

    // ── Guard: no session found in mapping ────────────────────────────────────

    @Test
    void submit_doesNothing_whenNoSessionFoundForCase() {
        // No session registered for this caseId
        manager.submit(null, caseInstance(UUID.randomUUID()), worker("agent"), null, Map.of())
                .await().indefinitely();

        verifyNoInteractions(tmuxService, eventBus);
    }

    // ── IO failure handling ────────────────────────────────────────────────────

    @Test
    void watcher_stopsWithoutPublishing_afterMaxConsecutiveIoFailures() throws Exception {
        var caseId = UUID.randomUUID();
        var sessionId = "ghi789";
        var sessionName = SESSION_PREFIX + sessionId;
        seedSession(sessionId, caseId, "validator");
        when(tmuxService.sessionExists(sessionName)).thenThrow(new IOException("tmux error"));

        manager.submit(null, caseInstance(caseId), worker("validator"), null, Map.of())
                .await().indefinitely();

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        verify(tmuxService, atLeast(3)).sessionExists(sessionName));

        Thread.sleep(200);
        verify(eventBus, never()).send(anyString(), any());
    }

    // ── Duplicate submit guard ─────────────────────────────────────────────────

    @Test
    void watch_ignoresDuplicateSubmit_onlyOneWatcherStarted() throws Exception {
        var caseId = UUID.randomUUID();
        var sessionId = "jkl000";
        var sessionName = SESSION_PREFIX + sessionId;
        seedSession(sessionId, caseId, "reviewer");

        // Block until we explicitly release — keeps first watcher alive
        var countdown = new java.util.concurrent.CountDownLatch(1);
        when(tmuxService.sessionExists(sessionName)).thenAnswer(inv -> {
            countdown.await();
            return false;
        });

        manager.submit(null, caseInstance(caseId), worker("reviewer"), null, Map.of())
                .await().indefinitely();
        manager.submit(null, caseInstance(caseId), worker("reviewer"), null, Map.of())
                .await().indefinitely();

        assertThat(manager.activeWatcherCount()).isEqualTo(1);
        countdown.countDown();
    }

    // ── getActiveWorkCount ─────────────────────────────────────────────────────

    @Test
    void getActiveWorkCount_returnsCountForRole() throws Exception {
        var caseId1 = UUID.randomUUID();
        var caseId2 = UUID.randomUUID();
        var sessionId1 = "role1-s1";
        var sessionId2 = "role1-s2";
        seedSession(sessionId1, caseId1, "agent");
        seedSession(sessionId2, caseId2, "agent");

        var countdown = new java.util.concurrent.CountDownLatch(1);
        when(tmuxService.sessionExists(anyString())).thenAnswer(inv -> {
            countdown.await();
            return false;
        });

        manager.submit(null, caseInstance(caseId1), worker("agent"), null, Map.of())
                .await().indefinitely();
        manager.submit(null, caseInstance(caseId2), worker("agent"), null, Map.of())
                .await().indefinitely();

        assertThat(manager.getActiveWorkCount("agent")).isEqualTo(2);
        assertThat(manager.getActiveWorkCount("other-role")).isEqualTo(0);
        countdown.countDown();
    }

    // ── schedulePersistedEvent — no-op ────────────────────────────────────────

    @Test
    void schedulePersistedEvent_isNoOp() {
        manager.schedulePersistedEvent(null).await().indefinitely();
        verifyNoInteractions(tmuxService, eventBus);
        assertThat(registry.all()).isEmpty();
    }

    // ── Shutdown hook ─────────────────────────────────────────────────────────

    @Test
    void shutdown_interruptsActiveWatchers() throws Exception {
        var caseId = UUID.randomUUID();
        var sessionId = "mno111";
        var sessionName = SESSION_PREFIX + sessionId;
        seedSession(sessionId, caseId, "auditor");

        // Block watcher indefinitely
        when(tmuxService.sessionExists(sessionName)).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return true;
        });

        manager.submit(null, caseInstance(caseId), worker("auditor"), null, Map.of())
                .await().indefinitely();

        Thread.sleep(100); // Let watcher start
        manager.shutdown();

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(manager.activeWatcherCount()).isZero());
    }

    // ── watch() — direct call (used by recovery path) ─────────────────────────

    @Test
    void watch_directCall_publishesWhenSessionGone() throws Exception {
        var caseId = UUID.randomUUID();
        var sessionId = "pqr222";
        var sessionName = SESSION_PREFIX + sessionId;
        // Register session directly in registry (recovery — no sessionMapping entry)
        registry.register(session(sessionId, caseId, "writer"));
        when(tmuxService.sessionExists(sessionName)).thenReturn(false);

        manager.watch(sessionId, sessionName, caseInstance(caseId), worker("writer"));

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        verify(eventBus).send(
                                eq(EventBusAddresses.WORKER_EXECUTION_FINISHED),
                                any(WorkflowExecutionCompleted.class)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void seedSession(String sessionId, UUID caseId, String roleName) {
        registry.register(session(sessionId, caseId, roleName));
        sessionMapping.register(roleName, caseId, sessionId);
    }

    private Session session(String id, UUID caseId, String roleName) {
        return new Session(id, SESSION_PREFIX + id, "/tmp", "claude",
                SessionStatus.IDLE, Instant.now(), Instant.now(),
                Optional.empty(), Optional.of(caseId.toString()), Optional.of(roleName),
                io.casehub.platform.api.identity.TenancyConstants.DEFAULT_TENANT_ID);
    }

    private CaseInstance caseInstance(UUID caseId) {
        var instance = new CaseInstance();
        instance.setUuid(caseId);
        return instance;
    }

    private Worker worker(String name) {
        return Worker.builder()
                .name(name)
                .capabilities(List.of(Capability.of(name, "{}", "{}")))
                .function(new WorkerFunction.Sync(ctx -> WorkerResult.of(Map.of())))
                .build();
    }

    // ── drainExitSignal ────────────────────────────────────────────────────────

    @Test
    void workerExit_storesPendingExitSignal() throws Exception {
        var caseId = UUID.randomUUID();
        var sessionId = "sig001";
        var sessionName = SESSION_PREFIX + sessionId;
        seedSession(sessionId, caseId, "agent");
        when(tmuxService.sessionExists(sessionName)).thenReturn(false);

        manager.watch(sessionId, sessionName, caseInstance(caseId), worker("agent"));

        // Wait for event bus send — signal is stored before send, so it's there too
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() ->
                        verify(eventBus).send(
                                eq(EventBusAddresses.WORKER_EXECUTION_FINISHED),
                                any(WorkflowExecutionCompleted.class)));

        assertThat(manager.drainExitSignal(caseId)).isEqualTo("agent");
        assertThat(manager.drainExitSignal(caseId)).isNull(); // drained — second call returns null
    }

    @Test
    void drainExitSignal_unknownCaseId_returnsNull() {
        assertThat(manager.drainExitSignal(UUID.randomUUID())).isNull();
    }
}
