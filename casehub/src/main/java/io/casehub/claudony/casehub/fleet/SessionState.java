package io.casehub.claudony.casehub.fleet;

/** Lifecycle state of a pooled agent session. ACTIVE sessions consume capacity; SUSPENDED sessions are on disk only. */
public enum SessionState {
    ACTIVE, SUSPENDED
}
