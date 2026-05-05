package dev.claudony.server.strategy;

/**
 * Extends EventsOnly — additionally reacts to any SessionRegistry mutation for case sessions.
 * CaseEventBroadcaster wires SessionRegistry.addChangeListener() when this strategy is active.
 * Provides instant accuracy regardless of whether state changes flow through CaseHub lifecycle events.
 */
public class RegistryHooksStrategy extends EventsOnlyStrategy {
    // All behaviour inherited from EventsOnlyStrategy.
    // CaseEventBroadcaster.@PostConstruct wires registry.addChangeListener(broadcaster::emit)
    // when this strategy is selected.
}
