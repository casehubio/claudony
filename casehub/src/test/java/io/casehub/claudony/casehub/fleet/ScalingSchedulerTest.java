package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class ScalingSchedulerTest {

    private AgentPoolDefinitionRegistry defRegistry;
    private AgentPoolManagerRegistry mgrRegistry;
    private AtomicInteger createCount;

    @BeforeEach
    void setUp() {
        defRegistry = new AgentPoolDefinitionRegistry();
        mgrRegistry = new AgentPoolManagerRegistry();
        createCount = new AtomicInteger();
    }

    private AgentSessionManager createManager(int min, int max) {
        return new AgentSessionManager(
            new AgentSessionManagerConfig(min, max),
            new SessionOperations() {
                @Override public String create(String i, String w) {
                    return "s-" + createCount.incrementAndGet();
                }
                @Override public String conversationId(String s) { return "c-" + s; }
                @Override public void suspend(String s) {}
                @Override public void resume(String s, String c, String w) {}
                @Override public void destroy(String s) {}
                @Override public long memoryBytes(String s) { return 0; }
            }
        );
    }

    @Test
    void tickEvaluatesPolicyAndAdjustsMax() {
        var def = AgentPoolDefinition.builder()
            .agent("reviewer")
            .pool()
                .maxActive(20)
                .scaling(new ScalingConfig.TargetTrackingConfig(0.7, null, null))
            .build();
        defRegistry.register(def);

        var manager = createManager(0, 20);
        manager.adjustMaxActive(10);
        mgrRegistry.register("reviewer", manager);

        for (int i = 0; i < 8; i++) {
            manager.acquireSession("reviewer", "/ws/" + i, null, WorkingDirPolicy.SHARED_READ);
        }

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry);
        scheduler.tick();

        assertThat(manager.status().max()).isGreaterThan(10);
    }

    @Test
    void tickSkipsPoolsWithNoScaling() {
        var def = AgentPoolDefinition.builder()
            .agent("reviewer")
            .pool().maxActive(10)
            .build();
        defRegistry.register(def);

        var manager = createManager(0, 10);
        mgrRegistry.register("reviewer", manager);

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry);
        scheduler.tick();

        assertThat(manager.status().max()).isEqualTo(10);
    }

    @Test
    void cooldownPreventsConsecutiveScaling() {
        var def = AgentPoolDefinition.builder()
            .agent("reviewer")
            .pool()
                .maxActive(30)
                .scaling(new ScalingConfig.TargetTrackingConfig(
                    0.7, Duration.ofSeconds(300), null))
            .build();
        defRegistry.register(def);

        var manager = createManager(0, 30);
        manager.adjustMaxActive(20);
        mgrRegistry.register("reviewer", manager);

        for (int i = 0; i < 16; i++) {
            manager.acquireSession("reviewer", "/ws/" + i, null, WorkingDirPolicy.SHARED_READ);
        }

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry);
        scheduler.tick();
        int maxAfterFirst = manager.status().max();
        assertThat(maxAfterFirst).isGreaterThan(20);

        scheduler.tick();
        assertThat(manager.status().max()).isEqualTo(maxAfterFirst);
    }

    @Test
    void scalingStateRetainedAfterTick() {
        var def = AgentPoolDefinition.builder()
                                     .agent("test").pool().maxActive(5)
                                     .scaling(new ScalingConfig.TargetTrackingConfig(0.5, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                                     .build();
        defRegistry.register(def);
        var mgr = createManager(0, 5);
        mgrRegistry.register("test", mgr);
        mgr.acquireSession("id", "/tmp");
        mgr.acquireSession("id2", "/tmp2");
        mgr.acquireSession("id3", "/tmp3");

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry);
        scheduler.tick();

        var state = scheduler.scalingState("test");
        assertThat(state).isPresent();
        assertThat(state.get().lastDecision()).isNotNull();
        assertThat(state.get().lastDecision().direction()).isEqualTo(ScalingDirection.OUT);
        assertThat(state.get().config()).isInstanceOf(ScalingConfig.TargetTrackingConfig.class);
    }

    @Test
    void scalingStateEmptyForUnknownPool() {
        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry);
        assertThat(scheduler.scalingState("nonexistent")).isEmpty();
    }

    @Test
    void scalingDecisionEmitsEvent() {
        var def = AgentPoolDefinition.builder()
                                     .agent("test").pool().maxActive(20)
                                     .scaling(new ScalingConfig.TargetTrackingConfig(0.7, null, null))
                                     .build();
        defRegistry.register(def);

        var manager = createManager(0, 20);
        manager.adjustMaxActive(10);
        mgrRegistry.register("test", manager);

        for (int i = 0; i < 8; i++) {
            manager.acquireSession("test", "/ws/" + i, null, WorkingDirPolicy.SHARED_READ);
        }

        var emittedTopics = new java.util.ArrayList<String>();
        var emitter = new PoolEventEmitter((topic, json) -> {
            emittedTopics.add(topic);
            return 1L;
        });

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry, emitter);
        scheduler.tick();

        assertThat(emittedTopics).containsExactly("pool:test:scaling");
    }

    @Test
    void noEventWhenNoScalingNeeded() {
        var def = AgentPoolDefinition.builder()
                                     .agent("test").pool().maxActive(10)
                                     .scaling(new ScalingConfig.TargetTrackingConfig(0.7, null, null))
                                     .build();
        defRegistry.register(def);

        var manager = createManager(0, 10);
        mgrRegistry.register("test", manager);
        for (int i = 0; i < 7; i++) {
            manager.acquireSession("test", "/ws/" + i, null, WorkingDirPolicy.SHARED_READ);
        }

        var emittedTopics = new java.util.ArrayList<String>();
        var emitter = new PoolEventEmitter((topic, json) -> {
            emittedTopics.add(topic);
            return 1L;
        });

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry, emitter);
        scheduler.tick();

        assertThat(emittedTopics).isEmpty();
    }

    @Test
    void tickCollectsExternalMetricsAndPassesToSnapshot() {
        var def = AgentPoolDefinition.builder()
                                     .agent("test").pool().maxActive(10)
                                     .scaling(new ScalingConfig.TargetTrackingConfig(0.7, null, null))
                                     .build();
        defRegistry.register(def);

        var manager = createManager(0, 10);
        mgrRegistry.register("test", manager);
        manager.acquireSession("test", "/ws/1", null, WorkingDirPolicy.SHARED_READ);

        DemandMetricsSource source = poolName ->
                                             java.util.Map.of("http.queue_depth", 3.0);

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry,
                                             null, java.util.List.of(source));
        scheduler.tick();

        var lastSnapshot = manager.lastDemandSnapshot();
        assertThat(lastSnapshot.externalMetrics())
                .containsEntry("http.queue_depth", 3.0);
    }

    @Test
    void tickHandlesFailingMetricsSourceGracefully() {
        var def = AgentPoolDefinition.builder()
                                     .agent("test").pool().maxActive(10)
                                     .scaling(new ScalingConfig.TargetTrackingConfig(0.7, null, null))
                                     .build();
        defRegistry.register(def);

        var manager = createManager(0, 10);
        mgrRegistry.register("test", manager);
        manager.acquireSession("test", "/ws/1", null, WorkingDirPolicy.SHARED_READ);

        DemandMetricsSource failingSource = poolName -> {
            throw new RuntimeException("source down");
        };
        DemandMetricsSource goodSource = poolName ->
                                                 java.util.Map.of("healthy", 1.0);

        var scheduler = new ScalingScheduler(defRegistry, mgrRegistry,
                                             null, java.util.List.of(failingSource, goodSource));
        scheduler.tick();

        var lastSnapshot = manager.lastDemandSnapshot();
        assertThat(lastSnapshot.externalMetrics())
                .containsEntry("healthy", 1.0);
    }
}
