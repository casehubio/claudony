package io.casehub.claudony.casehub.fleet;

public class BudgetExceededException extends AgentPoolExhaustedException {

    public BudgetExceededException(AgentPoolStatus poolStatus) {
        super(poolStatus);
    }
}
