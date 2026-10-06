package io.casehub.claudony.casehub.fleet.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.jackson.YamlMappers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FleetScriptParser {

    private static final ObjectMapper YAML = YamlMappers.create();
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{var\\.([^}]+)}");

    public FleetScript parse(String yaml) {
        try {
            return YAML.readValue(yaml, FleetScript.class);
        } catch (IOException e) {
            if (e.getCause() instanceof IllegalArgumentException iae) {
                throw iae;
            }
            throw new UncheckedIOException("Failed to parse fleet script YAML", e);
        }
    }

    public FleetScript substituteVariables(FleetScript script) {
        var vars = script.variables();
        if (vars == null) vars = Map.of();

        var newNodes = new LinkedHashMap<String, FleetNode>();
        for (var entry : script.nodes().entrySet()) {
            var node = entry.getValue();
            var newSpec = substituteMap(node.spec(), vars);
            newNodes.put(entry.getKey(), new FleetNode(node.type(), newSpec, node.dependsOn()));
        }
        return new FleetScript(script.desiredState(), script.variables(), newNodes);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> substituteMap(Map<String, Object> map, Map<String, String> vars) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            result.put(entry.getKey(), substituteValue(entry.getValue(), vars));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object substituteValue(Object value, Map<String, String> vars) {
        if (value instanceof String s) {
            return substituteString(s, vars);
        } else if (value instanceof Map<?, ?> m) {
            return substituteMap((Map<String, Object>) m, vars);
        } else if (value instanceof List<?> list) {
            return list.stream().map(item -> substituteValue(item, vars)).toList();
        }
        return value;
    }

    private String substituteString(String s, Map<String, String> vars) {
        var matcher = VAR_PATTERN.matcher(s);
        var sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = vars.get(varName);
            if (replacement == null) {
                throw new IllegalArgumentException("Undefined variable: " + varName);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
