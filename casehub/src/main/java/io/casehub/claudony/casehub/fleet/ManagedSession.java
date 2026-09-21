package io.casehub.claudony.casehub.fleet;

import java.time.Instant;

public final class ManagedSession {

    private final String instanceId;
    private final String identity;
    private final String workingDir;
    private String conversationId;
    private SessionState state;
    private Instant lastInteraction;
    private long lastMemoryBytes;

    public ManagedSession(String instanceId, String identity, String workingDir,
                          String conversationId) {
        this.instanceId = instanceId;
        this.identity = identity;
        this.workingDir = workingDir;
        this.conversationId = conversationId;
        this.state = SessionState.ACTIVE;
        this.lastInteraction = Instant.now();
        this.lastMemoryBytes = 0;
    }

    public String instanceId() { return instanceId; }
    public String identity() { return identity; }
    public String workingDir() { return workingDir; }
    public String conversationId() { return conversationId; }
    public SessionState state() { return state; }
    public Instant lastInteraction() { return lastInteraction; }
    public long lastMemoryBytes() { return lastMemoryBytes; }

    void setState(SessionState state) { this.state = state; }
    void setConversationId(String conversationId) { this.conversationId = conversationId; }

    void recordInteraction(long memoryBytes) {
        this.lastInteraction = Instant.now();
        this.lastMemoryBytes = memoryBytes;
    }

    double evictionScore(Instant now) {
        long   idleSeconds = java.time.Duration.between(lastInteraction, now).toSeconds();
        double memoryMB    = lastMemoryBytes / (1024.0 * 1024.0);
        return idleSeconds + (memoryMB / 10.0);
    }
}
