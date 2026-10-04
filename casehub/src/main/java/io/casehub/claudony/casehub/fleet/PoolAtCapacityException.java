package io.casehub.claudony.casehub.fleet;

public class PoolAtCapacityException extends AgentPoolExhaustedException {

    public PoolAtCapacityException(AgentPoolStatus poolStatus) {
        super(poolStatus);
    }
}
