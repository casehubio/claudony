package io.casehub.claudony.testing.fleet;

import io.casehub.claudony.casehub.fleet.AgentSessionManager;

public record TestPool(AgentSessionManager manager, InMemorySessionOperations ops) {}
