package dev.claudony.server.fleet;
public record AddPeerRequest(String url, String name, TerminalMode terminalMode) {}
