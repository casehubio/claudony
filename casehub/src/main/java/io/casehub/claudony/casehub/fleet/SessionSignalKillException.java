package io.casehub.claudony.casehub.fleet;

public class SessionSignalKillException extends RuntimeException {

    private final String sessionId;
    private final int exitCode;

    public SessionSignalKillException(String sessionId, int exitCode) {
        super("Session " + sessionId + " killed by signal (exit " + exitCode + ") — not model-related");
        this.sessionId = sessionId;
        this.exitCode = exitCode;
    }

    public String sessionId() {
        return sessionId;
    }

    public int exitCode() {
        return exitCode;
    }

    public int signal() {
        return exitCode - 128;
    }
}
