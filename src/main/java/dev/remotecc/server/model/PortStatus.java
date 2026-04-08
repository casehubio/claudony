package dev.remotecc.server.model;

public record PortStatus(int port, boolean up, long responseMs) {}
