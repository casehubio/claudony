package io.casehub.claudony.casehub.fleet;

/** Thrown when all active slots are consumed and minActive prevents eviction. Carries the current {@link AgentPoolStatus}. */
public class AgentPoolExhaustedException extends RuntimeException {

    private final AgentPoolStatus poolStatus;

    public AgentPoolExhaustedException(AgentPoolStatus poolStatus) {
        super("Agent pool exhausted: " + poolStatus.active() + "/" + poolStatus.max() + " active");
        this.poolStatus = poolStatus;
    }

    public AgentPoolStatus poolStatus() {
        return poolStatus;
    }
}
