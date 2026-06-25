package io.casehub.claudony.casehub;

import java.util.Set;

public interface ProviderConfigSource {
    ClaudonyProviderConfig forAgent(String agentId);
    Set<String> declaredAgentIds();
}
