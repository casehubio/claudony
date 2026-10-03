package io.casehub.claudony.casehub.fleet.script;

import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class FleetScriptRunner {

    private static final Logger LOG = Logger.getLogger(FleetScriptRunner.class);

    private final Map<String, FleetNodeHandler> handlers;
    private final FleetScriptParser parser;

    public FleetScriptRunner(List<FleetNodeHandler> handlers) {
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(FleetNodeHandler::type, h -> h,
                        (a, b) -> { throw new IllegalArgumentException("Duplicate handler for type: " + a.type()); }));
        this.parser = new FleetScriptParser();
    }

    public FleetScriptResult execute(String yaml) {
        var script = parser.parse(yaml);
        script = parser.substituteVariables(script);
        var sorted = topologicalSort(script.nodes());
        return executeInOrder(sorted, script.nodes());
    }

    List<String> topologicalSort(Map<String, FleetNode> nodes) {
        var inDegree = new LinkedHashMap<String, Integer>();
        var adjacency = new LinkedHashMap<String, List<String>>();

        for (var name : nodes.keySet()) {
            inDegree.put(name, 0);
            adjacency.put(name, new ArrayList<>());
        }

        for (var entry : nodes.entrySet()) {
            for (String dep : entry.getValue().dependsOn()) {
                if (!nodes.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "Node '" + entry.getKey() + "' depends on unknown node '" + dep + "'");
                }
                adjacency.get(dep).add(entry.getKey());
                inDegree.merge(entry.getKey(), 1, Integer::sum);
            }
        }

        var queue = new ArrayDeque<String>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        var result = new ArrayList<String>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            result.add(node);
            for (String neighbor : adjacency.get(node)) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) queue.add(neighbor);
            }
        }

        if (result.size() != nodes.size()) {
            var remaining = new LinkedHashSet<>(nodes.keySet());
            remaining.removeAll(new LinkedHashSet<>(result));
            throw new IllegalArgumentException("Dependency cycle detected among nodes: " + remaining);
        }

        return result;
    }

    private FleetScriptResult executeInOrder(List<String> sorted, Map<String, FleetNode> nodes) {
        var results = new ArrayList<NodeResult>();
        var failed = new HashSet<String>();

        for (String name : sorted) {
            var node = nodes.get(name);

            var failedDep = node.dependsOn().stream()
                    .filter(failed::contains)
                    .findFirst();
            if (failedDep.isPresent()) {
                results.add(NodeResult.failed(name, node.type(),
                        "dependency '" + failedDep.get() + "' failed"));
                failed.add(name);
                continue;
            }

            var handler = handlers.get(node.type());
            if (handler == null) {
                LOG.warnf("No handler for node type '%s' — skipping node '%s'", node.type(), name);
                results.add(NodeResult.skipped(name, node.type(),
                        "no handler for type '" + node.type() + "'"));
                continue;
            }

            try {
                var result = handler.handle(name, node.spec());
                results.add(result);
                if (!result.success()) failed.add(name);
            } catch (Exception e) {
                LOG.errorf(e, "Handler for node '%s' threw exception", name);
                results.add(NodeResult.failed(name, node.type(), e.getMessage()));
                failed.add(name);
            }
        }

        return new FleetScriptResult(results);
    }
}
