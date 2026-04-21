package dev.claudony.server.model;

public record CreateSessionRequest(
        String name,
        String workingDir,
        String command,
        String expiryPolicy) {

    public String effectiveCommand(String defaultCommand) {
        return (command != null && !command.isBlank()) ? command : defaultCommand;
    }
}
