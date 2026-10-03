package io.casehub.claudony.casehub.fleet.script;

import java.util.Objects;

public record NodeResult(String name, String type, boolean success, String message) {
    public NodeResult {
        Objects.requireNonNull(name);
        Objects.requireNonNull(type);
    }

    public static NodeResult ok(String name, String type, String message) {
        return new NodeResult(name, type, true, message);
    }

    public static NodeResult failed(String name, String type, String message) {
        return new NodeResult(name, type, false, message);
    }

    public static NodeResult skipped(String name, String type, String reason) {
        return new NodeResult(name, type, true, "skipped: " + reason);
    }
}
