package io.casehub.claudony.casehub.fleet;

import java.time.Instant;

/** Scores sessions for eviction — highest score gets evicted first. */
public interface EvictionPolicy {
    double score(ManagedSession session, Instant now);
}
