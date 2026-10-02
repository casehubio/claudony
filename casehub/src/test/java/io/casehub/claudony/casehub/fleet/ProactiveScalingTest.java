package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProactiveScalingTest {

    private AgentPoolDefinitionRegistry defRegistry;
    private AgentPoolManagerRegistry mgrRegistry;
    private AtomicInteger createCount;
    private ConcurrentHashMap<String, String> convIds;

    @BeforeEach
    void setUp() {
        defRegistry = new AgentPoolDefinitionRegistry();
        mgrRegistry = new AgentPoolManagerRegistry();
        createCount = new AtomicInteger();
        convIds = new ConcurrentHashMap<>();
    }

    private AgentSessionManager createManager(int min, int max) {
        return new AgentSessionManager(
            new AgentSessionManagerConfig(min, max),
            new SessionOperations() {
                @Override public String create(String i, String w) {
                    String id = "s-" + createCount.incrementAndGet();
                    convIds.put(id, "c-" + id);
                    return id;
                }
                @Override public String conversationId(String s) { return convIds.get(s); }
                @Override public void suspend(String s) {}
                @Override public void resume(String s, String c, String w) {}
                @Override public void destroy(String s) {}
                @Override public long memoryBytes(String s) { return 0; }
            }
        );
    }

    @Test
    void proactive_resumesSuspendedSessions() {
        var def = AgentPoolDefinition.builder()
            .agent("reviewer")
            .pool()
                .maxActive(10)
                .scaling(new ScalingConfig.ProactiveConfig(2, null, null))
            .build();
        defRegistry.register(def);

        var manager = createManager(0, 10);
        mgrRegistry.register("reviewer", manager);

        var s1 = manager.acquireSession("r1", "/ws/1", null, WorkingDirPolicy.SHARED_READ);
        var s2 = manager.acquireSession("r2", "/ws/2", null, WorkingDirPolicy.SHARED_READ);
        var s3 = manager.acquireSession("r3", "/ws/3", null, WorkingDirPolicy.SHARED_READ);
        manager.suspendSession(s1.instanceId());
        manager.suspendSession(s2.instanceId());
        manager.suspendSession(s3.instanceId());

        assertThat(manager.activeCount()).isZero();
        assertThat(manager.suspendedCount()).isEqualTo(3);

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry);
        scheduler.tick();

        assertThat(manager.activeCount()).isEqualTo(2);
        assertThat(manager.suspendedCount()).isEqualTo(1);
    }

    @Test
    void proactive_noActionWhenAtTarget() {
        var def = AgentPoolDefinition.builder()
            .agent("reviewer")
            .pool()
                .maxActive(10)
                .scaling(new ScalingConfig.ProactiveConfig(2, null, null))
            .build();
        defRegistry.register(def);

        var manager = createManager(0, 10);
        mgrRegistry.register("reviewer", manager);

        manager.acquireSession("r1", "/ws/1", null, WorkingDirPolicy.SHARED_READ);
        manager.acquireSession("r2", "/ws/2", null, WorkingDirPolicy.SHARED_READ);

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry);
        scheduler.tick();

        assertThat(manager.activeCount()).isEqualTo(2);
    }

    @Test
    void proactive_respectsMaxActive() {
        var def = AgentPoolDefinition.builder()
            .agent("reviewer")
            .pool()
                .maxActive(3)
                .scaling(new ScalingConfig.ProactiveConfig(5, null, null))
            .build();
        defRegistry.register(def);

        var manager = createManager(0, 3);
        mgrRegistry.register("reviewer", manager);

        for (int i = 0; i < 5; i++) {
            var s = manager.acquireSession("r" + i, "/ws/" + i, null, WorkingDirPolicy.SHARED_READ);
            manager.suspendSession(s.instanceId());
        }

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry);
        scheduler.tick();

        assertThat(manager.activeCount()).isEqualTo(3);
    }

    @Test
    void proactive_resumesMostRecentFirst() {
        var def = AgentPoolDefinition.builder()
            .agent("reviewer")
            .pool()
                .maxActive(10)
                .scaling(new ScalingConfig.ProactiveConfig(1, null, null))
            .build();
        defRegistry.register(def);

        var manager = createManager(0, 10);
        mgrRegistry.register("reviewer", manager);

        var s1 = manager.acquireSession("oldest", "/ws/1", null, WorkingDirPolicy.SHARED_READ);
        var s2 = manager.acquireSession("middle", "/ws/2", null, WorkingDirPolicy.SHARED_READ);
        var s3 = manager.acquireSession("newest", "/ws/3", null, WorkingDirPolicy.SHARED_READ);

        manager.suspendSession(s1.instanceId());
        manager.suspendSession(s2.instanceId());
        manager.suspendSession(s3.instanceId());

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry);
        scheduler.tick();

        assertThat(manager.activeCount()).isEqualTo(1);
        assertThat(manager.getSession(s3.instanceId()).state()).isEqualTo(SessionState.ACTIVE);
        assertThat(manager.getSession(s1.instanceId()).state()).isEqualTo(SessionState.SUSPENDED);
        assertThat(manager.getSession(s2.instanceId()).state()).isEqualTo(SessionState.SUSPENDED);
    }

    @Test
    void proactive_cooldownPreventsRapidWarming() {
        var def = AgentPoolDefinition.builder()
            .agent("reviewer")
            .pool()
                .maxActive(10)
                .scaling(new ScalingConfig.ProactiveConfig(2, Duration.ofMinutes(5), null))
            .build();
        defRegistry.register(def);

        var manager = createManager(0, 10);
        mgrRegistry.register("reviewer", manager);

        var s1 = manager.acquireSession("r1", "/ws/1", null, WorkingDirPolicy.SHARED_READ);
        var s2 = manager.acquireSession("r2", "/ws/2", null, WorkingDirPolicy.SHARED_READ);
        var s3 = manager.acquireSession("r3", "/ws/3", null, WorkingDirPolicy.SHARED_READ);
        manager.suspendSession(s1.instanceId());
        manager.suspendSession(s2.instanceId());
        manager.suspendSession(s3.instanceId());

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry);

        scheduler.tick();
        assertThat(manager.activeCount()).isEqualTo(2);

        manager.sessions().stream()
            .filter(s -> s.state() == SessionState.ACTIVE)
            .forEach(s -> manager.suspendSession(s.instanceId()));
        assertThat(manager.activeCount()).isZero();

        scheduler.tick();
        assertThat(manager.activeCount()).isZero();
    }

    @Test
    void proactive_emitsScalingEvent() {
        var def = AgentPoolDefinition.builder()
            .agent("reviewer")
            .pool()
                .maxActive(10)
                .scaling(new ScalingConfig.ProactiveConfig(1, null, null))
            .build();
        defRegistry.register(def);

        var manager = createManager(0, 10);
        mgrRegistry.register("reviewer", manager);

        var s1 = manager.acquireSession("r1", "/ws/1", null, WorkingDirPolicy.SHARED_READ);
        manager.suspendSession(s1.instanceId());

        var emittedTopics = new ArrayList<String>();
        var emitter = new PoolEventEmitter(
                (topic, json) -> { emittedTopics.add(topic); return 0L; });
        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry, emitter);
        scheduler.tick();

        assertThat(emittedTopics).containsExactly("pool:reviewer:scaling");
        var state = scheduler.scalingState("reviewer");
        assertThat(state).isPresent();
        assertThat(state.get().lastDecision().direction()).isEqualTo(ScalingDirection.PREWARM);
        assertThat(state.get().lastDecision().count()).isEqualTo(1);
    }
}
