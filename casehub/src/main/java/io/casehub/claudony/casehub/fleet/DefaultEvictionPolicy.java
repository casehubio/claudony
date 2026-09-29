package io.casehub.claudony.casehub.fleet;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;

/** Idle-time × memory-pressure scoring — preserves the original hardcoded formula. */
@ApplicationScoped
@DefaultBean
public class DefaultEvictionPolicy implements EvictionPolicy {

    @Override
    public double score(ManagedSession session, Instant now) {
        long idleSeconds = Duration.between(session.lastInteraction(), now).toSeconds();
        double memoryMB = session.lastMemoryBytes() / (1024.0 * 1024.0);
        return (idleSeconds + 1) * (1.0 + memoryMB / 100.0);
    }
}
