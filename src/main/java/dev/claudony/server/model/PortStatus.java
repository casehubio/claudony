package dev.claudony.server.model;

public record PortStatus(int port, boolean up, long responseMs) {}
