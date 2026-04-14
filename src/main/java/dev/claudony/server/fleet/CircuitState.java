package dev.claudony.server.fleet;

/** Circuit breaker state per peer. OPEN = peer skipped; HALF_OPEN = testing recovery. */
public enum CircuitState { CLOSED, OPEN, HALF_OPEN }
