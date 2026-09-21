package io.casehub.claudony.casehub.fleet;

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
