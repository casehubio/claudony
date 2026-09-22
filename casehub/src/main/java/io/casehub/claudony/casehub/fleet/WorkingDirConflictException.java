package io.casehub.claudony.casehub.fleet;

public class WorkingDirConflictException extends RuntimeException {

    private final String workingDir;
    private final String existingIdentity;

    public WorkingDirConflictException(String workingDir, String existingIdentity) {
        super("Working directory '" + workingDir + "' already has an active session (identity: " + existingIdentity + ")");
        this.workingDir = workingDir;
        this.existingIdentity = existingIdentity;
    }

    public String workingDir() { return workingDir; }
    public String existingIdentity() { return existingIdentity; }
}