package io.casehub.claudony.casehub.fleet;

import java.time.Instant;

public class PoolEventEmitter {

    @FunctionalInterface
    public interface Broadcaster {
        long broadcast(String topic, String payloadJson);
    }

    @FunctionalInterface
    public interface EventCallback {
        void onEvent(String poolName, String payloadJson);
    }

    private final Broadcaster broadcaster;
    private final EventCallback eventCallback;

    public PoolEventEmitter(Broadcaster broadcaster) {
        this(broadcaster, null);
    }

    public PoolEventEmitter(Broadcaster broadcaster, EventCallback eventCallback) {
        this.broadcaster = broadcaster;
        this.eventCallback = eventCallback;
    }

    public void emitScalingDecision(String pool, ScalingDecision decision, int previousMax, int newMax) {
        var json = String.format(
            "{\"type\":\"scaling\",\"direction\":\"%s\",\"count\":%d,\"reason\":\"%s\",\"previousMax\":%d,\"newMax\":%d,\"timestamp\":\"%s\"}",
            decision.direction(), decision.count(), escapeJson(decision.reason()),
            previousMax, newMax, Instant.now());
        broadcaster.broadcast("pool:" + pool + ":scaling", json);
        if (eventCallback != null) eventCallback.onEvent(pool, json);
    }

    public void emitSessionEvent(String pool, String event, String instanceId, String identity, String reason) {
        var json = String.format(
            "{\"type\":\"session\",\"event\":\"%s\",\"instanceId\":\"%s\",\"identity\":\"%s\",\"reason\":\"%s\",\"timestamp\":\"%s\"}",
            event, instanceId, identity != null ? identity : "", escapeJson(reason), Instant.now());
        broadcaster.broadcast("pool:" + pool + ":session", json);
        if (eventCallback != null) eventCallback.onEvent(pool, json);
    }

    public void emitHealthChange(String pool, String previous, String current, String reason) {
        var json = String.format(
            "{\"type\":\"health\",\"previous\":\"%s\",\"current\":\"%s\",\"reason\":\"%s\",\"timestamp\":\"%s\"}",
            previous, current, escapeJson(reason), Instant.now());
        broadcaster.broadcast("pool:" + pool + ":health", json);
        if (eventCallback != null) eventCallback.onEvent(pool, json);
    }

    private String escapeJson(String s) {
        if (s == null) {return "";}
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
