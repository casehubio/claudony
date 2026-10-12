package io.casehub.claudony.server.ops;

public record PeerOpsView(String id, String name, String url, String health,
                           String circuitState, int sessionCount, boolean stale) {}
