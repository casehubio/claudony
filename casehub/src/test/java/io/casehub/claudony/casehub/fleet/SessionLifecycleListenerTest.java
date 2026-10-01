package io.casehub.claudony.casehub.fleet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionLifecycleListenerTest {

    private static SessionOperations stubOps() {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        var convIds = new java.util.concurrent.ConcurrentHashMap<String, String>();
        return new SessionOperations() {
            @Override
            public String create(String identity, String workingDir) {
                String id = "session-" + counter.incrementAndGet();
                convIds.put(id, "conv-" + id);
                return id;
            }

            @Override
            public String conversationId(String sessionId)                                 {return convIds.get(sessionId);}

            @Override
            public void suspend(String sessionId)                                          {}

            @Override
            public void resume(String sessionId, String conversationId, String workingDir) {}

            @Override
            public void destroy(String sessionId)                                          {}

            @Override
            public long memoryBytes(String sessionId)                                      {return 0;}
        };
    }

    @Test
    void acquire_notifiesListener() {
        var events   = new ArrayList<String>();
        var listener = new RecordingListener(events);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5),
                stubOps(), listener, "test-pool");

        var session = manager.acquireSession("worker-1", "/tmp");

        assertThat(events).containsExactly("acquired:worker-1:test-pool");
        assertThat(session).isNotNull();
    }

    @Test
    void suspend_notifiesListener() {
        var events   = new ArrayList<String>();
        var listener = new RecordingListener(events);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), stubOps(), listener, "test-pool");

        var session = manager.acquireSession("worker-1", "/tmp");
        events.clear();

        manager.suspendSession(session.instanceId());

        assertThat(events).containsExactly("suspended:worker-1:test-pool");
    }

    @Test
    void resume_notifiesListener() {
        var events   = new ArrayList<String>();
        var listener = new RecordingListener(events);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), stubOps(), listener, "test-pool");

        var session = manager.acquireSession("worker-1", "/tmp");
        manager.suspendSession(session.instanceId());
        events.clear();

        manager.resumeSession(session.instanceId());

        assertThat(events).containsExactly("resumed:worker-1:test-pool");
    }

    @Test
    void destroy_notifiesListener() {
        var events   = new ArrayList<String>();
        var listener = new RecordingListener(events);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), stubOps(), listener, "test-pool");

        var    session = manager.acquireSession("worker-1", "/tmp");
        String id      = session.instanceId();
        events.clear();

        manager.destroySession(id);

        assertThat(events).containsExactly("destroyed:" + id + ":test-pool");
    }

    @Test
    void shutdown_notifiesListenerForEachSession() {
        var events   = new ArrayList<String>();
        var listener = new RecordingListener(events);
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), stubOps(), listener, "test-pool");

        manager.acquireSession("worker-1", "/tmp");
        manager.acquireSession("worker-2", "/tmp/other");
        events.clear();

        manager.shutdown();

        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.startsWith("destroyed:") && e.endsWith(":test-pool"));
    }

    @Test
    void noopListener_doesNotThrow() {
        var manager = new AgentSessionManager(
                new AgentSessionManagerConfig(0, 5), stubOps());

        var session = manager.acquireSession("worker-1", "/tmp");
        manager.suspendSession(session.instanceId());
        manager.resumeSession(session.instanceId());
        manager.destroySession(session.instanceId());
    }

    private record RecordingListener(List<String> events) implements SessionLifecycleListener {
        @Override
        public void onAcquired(ManagedSession session, String poolName) {
            events.add("acquired:" + session.identity() + ":" + poolName);
        }

        @Override
        public void onSuspended(ManagedSession session, String poolName) {
            events.add("suspended:" + session.identity() + ":" + poolName);
        }

        @Override
        public void onResumed(ManagedSession session, String poolName) {
            events.add("resumed:" + session.identity() + ":" + poolName);
        }

        @Override
        public void onDestroyed(String sessionId, String poolName) {
            events.add("destroyed:" + sessionId + ":" + poolName);
        }
    }
}
