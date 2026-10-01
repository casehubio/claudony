package io.casehub.claudony.casehub.fleet;

public interface SessionLifecycleListener {

    SessionLifecycleListener NOOP = new SessionLifecycleListener() {};

    default void onAcquired(ManagedSession session, String poolName) {}
    default void onSuspended(ManagedSession session, String poolName) {}
    default void onResumed(ManagedSession session, String poolName) {}
    default void onDestroyed(String sessionId, String poolName) {}
}
