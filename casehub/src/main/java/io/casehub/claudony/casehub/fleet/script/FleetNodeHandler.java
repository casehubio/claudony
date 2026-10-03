package io.casehub.claudony.casehub.fleet.script;

import java.util.Map;

public interface FleetNodeHandler {
    String type();
    NodeResult handle(String nodeName, Map<String, Object> spec);
}
