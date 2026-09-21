package io.casehub.claudony.casehub.fleet;

public interface SessionOperations {
    String create(String identity, String workingDir);
    String conversationId(String sessionId);
    void suspend(String sessionId);
    void resume(String sessionId, String conversationId, String workingDir);
    void destroy(String sessionId);
    long memoryBytes(String sessionId);
}
