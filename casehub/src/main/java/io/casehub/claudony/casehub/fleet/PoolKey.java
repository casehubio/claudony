package io.casehub.claudony.casehub.fleet;

import java.util.Objects;

record PoolKey(String agentName, String tenancyId) {
    PoolKey {
        Objects.requireNonNull(agentName, "agentName is required");
        Objects.requireNonNull(tenancyId, "tenancyId is required");
    }

    static String poolName(String agentName, String tenancyId) {
        return "default".equals(tenancyId) ? agentName : agentName + "@" + tenancyId;
    }
}
