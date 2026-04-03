package dev.remotecc.server.model;

public enum SessionStatus {
    ACTIVE,   // Claude is actively responding
    WAITING,  // Claude has shown a prompt, waiting for user input
    IDLE      // Shell prompt visible, no Claude running
}
