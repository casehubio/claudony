package io.casehub.claudony.server.ops;

public record FleetHealth(int totalEntities, int healthy, int degraded, int down, int orphaned) {}
