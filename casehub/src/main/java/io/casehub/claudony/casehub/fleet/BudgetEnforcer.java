package io.casehub.claudony.casehub.fleet;

import java.util.List;

@jakarta.enterprise.context.ApplicationScoped
public class BudgetEnforcer {

    void apply(String poolName, EnforcementPolicy policy, BudgetCheckResult result,
               AgentSessionManager manager) {
        manager.setBudgetLocked(true);
        switch (policy) {
            case SUSPEND -> {
                List<ManagedSession> active = manager.sessions().stream()
                        .filter(s -> s.state() == SessionState.ACTIVE)
                        .toList();
                for (var session : active) {
                    manager.suspendSession(session.instanceId());
                }
                manager.adjustMaxActive(0);
            }
            case BLOCK_NEW -> manager.adjustMaxActive(0);
            case ALERT -> manager.adjustMaxActive(manager.config().minActive());
        }
    }
}
