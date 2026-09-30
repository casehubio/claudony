package io.casehub.claudony.server.push;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

@ApplicationScoped
public class ClaudonySessionSender {

    private static final Logger                        LOG         = Logger.getLogger(ClaudonySessionSender.class.getName());
    private final        Map<String, Consumer<String>> connections = new ConcurrentHashMap<>();

    public void send(String connectionId, String message) {
        var handler = connections.get(connectionId);
        if (handler != null) {
            try {
                handler.accept(message);
            } catch (Exception e) {
                LOG.fine("Failed to send to " + connectionId + ": " + e.getMessage());
                connections.remove(connectionId);
            }
        }
    }

    public void register(String connectionId, Consumer<String> handler) {
        connections.put(connectionId, handler);
    }

    public void unregister(String connectionId) {
        connections.remove(connectionId);
    }

    public int connectionCount() {
        return connections.size();
    }
}
