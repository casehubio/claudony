# Fleet Script Runner — Design Spec

**Issue:** casehubio/claudony#247
**Date:** 2026-10-03
**Branch:** feat/247-fleet-script-runner

## Summary

A standalone fleet script runner that parses the #246 desiredstate YAML format,
topologically sorts nodes by `dependsOn` edges, and provisions pools and channels
in order via per-type handlers. One-shot execution — no reconciliation loop, no
drift detection.

## YAML Format

Accepts the full #246 YAML schema. The `desiredState` header is parsed but
ignored by the runner (it exists for forward-compatibility with ops reconciliation).

```yaml
desiredState:
  namespace: fleet
  name: code-review

variables:
  model_version: claude-opus-4-6
  workspace: ~/workspace/reviews
  mesh_prefix: team/code-review

nodes:
  code-reviewer-pool:
    type: pool
    dependsOn: []
    spec:
      agentId: code-reviewer
      minActive: 2
      maxActive: 8
      workingDir: ${var.workspace}
      workingDirPolicy: SHARED_READ
      scaling:
        type: target-tracking
        target: 0.7
        cooldown: 30s
      eviction: MEMORY_WEIGHTED

  review-channel:
    type: channel
    dependsOn: [code-reviewer-pool]
    spec:
      name: ${var.mesh_prefix}/reviews
      description: Code review coordination
      semantic: APPEND
      allowedTypes: [COMMAND, RESPONSE, STATUS, HANDOFF]
```

## Module Split

### claudony-casehub — `io.casehub.claudony.casehub.fleet.script`

Framework classes. No app-level dependencies.

#### FleetScript

Jackson-mapped record for the YAML:

```java
public record FleetScript(
    DesiredStateHeader desiredState,
    Map<String, String> variables,
    Map<String, FleetNode> nodes
) {
    public record DesiredStateHeader(String namespace, String name) {}
}
```

`desiredState` and `variables` are nullable. `nodes` is required.

#### FleetNode

```java
public record FleetNode(
    String type,
    Map<String, Object> spec,
    List<String> dependsOn
) {
    public FleetNode {
        if (type == null || type.isBlank())
            throw new IllegalArgumentException("node type is required");
        if (spec == null) spec = Map.of();
        if (dependsOn == null) dependsOn = List.of();
    }
}
```

#### FleetNodeHandler (SPI)

```java
public interface FleetNodeHandler {
    String type();
    NodeResult handle(String nodeName, Map<String, Object> spec);
}
```

Handlers are discovered via CDI `Instance<FleetNodeHandler>`. Unknown types
produce a warning result, not a failure.

#### NodeResult

```java
public record NodeResult(
    String name,
    String type,
    boolean success,
    String message
) {
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
```

#### FleetScriptResult

```java
public record FleetScriptResult(List<NodeResult> results) {
    public boolean allSucceeded() {
        return results.stream().allMatch(NodeResult::success);
    }
    public List<NodeResult> failures() {
        return results.stream().filter(r -> !r.success()).toList();
    }
}
```

#### FleetScriptParser

Handles YAML parsing and variable substitution:

```java
public class FleetScriptParser {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{var\\.([^}]+)}");

    public FleetScript parse(String yaml) { ... }
    public FleetScript substituteVariables(FleetScript script) { ... }
}
```

Variable substitution: walk all string values in all node specs, replace
`${var.xxx}` with the corresponding entry from `variables`. Non-string values
(numbers, booleans, lists, maps) are walked recursively — only leaf strings
are substituted. Missing variables throw `IllegalArgumentException`.

#### FleetScriptRunner

Core executor. Not `@ApplicationScoped` — instantiated with a handler map so
tests can pass mock handlers. An `@ApplicationScoped` CDI wrapper in the app
module provides the injected version.

```java
public class FleetScriptRunner {

    private final Map<String, FleetNodeHandler> handlers;
    private final FleetScriptParser parser;

    public FleetScriptRunner(List<FleetNodeHandler> handlers) {
        this.handlers = handlers.stream()
            .collect(Collectors.toMap(FleetNodeHandler::type, h -> h));
        this.parser = new FleetScriptParser();
    }

    public FleetScriptResult execute(String yaml) {
        var script = parser.parse(yaml);
        script = parser.substituteVariables(script);
        var sorted = topologicalSort(script.nodes());
        return executeInOrder(sorted, script.nodes());
    }
}
```

**Topological sort:** Kahn's algorithm. Cycle detection throws
`IllegalArgumentException` naming the cycle participants. Nodes at the same
dependency level execute sequentially (no parallelism needed for one-shot).

**Dependency failure cascading:** If node A fails and node B `dependsOn: [A]`,
node B is skipped with `NodeResult.failed(name, type, "dependency 'A' failed")`.
Nodes without dependency on the failure continue executing.

### claudony-app — `io.casehub.claudony.server.fleet`

Concrete handlers and REST endpoint.

#### PoolNodeHandler

```java
@ApplicationScoped
public class PoolNodeHandler implements FleetNodeHandler {

    @Inject AgentPoolDefinitionRegistry defRegistry;
    @Inject AgentPoolManagerRegistry mgrRegistry;
    @Inject ClaudonyAgentBackend agentBackend;

    @Override public String type() { return "pool"; }

    @Override
    public NodeResult handle(String nodeName, Map<String, Object> spec) {
        // Map spec to AgentPoolDefinition via AgentPoolYamlParser-style logic
        // Call agentBackend.fromDefinition() or equivalent wiring
        // Register into defRegistry + mgrRegistry
        // Return NodeResult.ok(...)
    }
}
```

Reuses the same spec-to-definition mapping as `AgentPoolYamlParser.toDefinition()`
— extracts `agentId`, `minActive`, `maxActive`, `workingDir`, `workingDirPolicy`,
`scaling`, `eviction` from the flat spec map. The `agentId` field in the spec
maps to the pool's agent name.

The wiring path: build `AgentPoolDefinition` → call
`ClaudonyAgentBackend.fromDefinition()` (creates `TmuxSessionOperations` +
`AgentSessionManager`, registers in `AgentPoolManagerRegistry`). Also registers
the definition in `AgentPoolDefinitionRegistry` for scaling scheduler visibility.

#### ChannelNodeHandler

```java
@ApplicationScoped
public class ChannelNodeHandler implements FleetNodeHandler {

    @Inject ChannelService channelService;

    @Override public String type() { return "channel"; }

    @Override
    public NodeResult handle(String nodeName, Map<String, Object> spec) {
        // Map spec to ChannelCreateRequest
        // Call channelService.create(request)
        // Return NodeResult.ok(...)
    }
}
```

Maps `name`, `description`, `semantic`, `allowedTypes` from the spec to a
`ChannelCreateRequest`. The `semantic` field maps to Qhorus `ChannelSemantic`
enum. The `allowedTypes` list maps to Qhorus `MessageType` enum values.

#### FleetScriptService

`@ApplicationScoped` CDI wrapper that injects all handlers and provides the
runner:

```java
@ApplicationScoped
public class FleetScriptService {

    private final FleetScriptRunner runner;

    @Inject
    public FleetScriptService(Instance<FleetNodeHandler> handlers) {
        this.runner = new FleetScriptRunner(
            handlers.stream().toList()
        );
    }

    public FleetScriptResult execute(String yaml) {
        return runner.execute(yaml);
    }
}
```

#### FleetResource

```java
@Path("/api/claudony/fleet")
@ApplicationScoped
public class FleetResource {

    @Inject FleetScriptService service;

    @POST
    @Path("/execute")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public FleetScriptResult execute(String yaml) {
        return service.execute(yaml);
    }
}
```

Accepts raw YAML as `text/plain` body, returns `FleetScriptResult` as JSON.

## Error Handling

| Scenario | Behaviour |
|----------|-----------|
| Malformed YAML | `FleetScriptParser.parse()` throws `UncheckedIOException` → 400 |
| Missing variable | `substituteVariables()` throws `IllegalArgumentException` → 400 |
| Dependency cycle | `topologicalSort()` throws `IllegalArgumentException` → 400 |
| Unknown node type | Warning log, `NodeResult.skipped()` in results |
| Handler failure | `NodeResult.failed()`, dependents cascade-skipped |
| All nodes succeed | 200 with `allSucceeded=true` |
| Partial failure | 200 with `allSucceeded=false`, failures listed |

## Test Plan

### Unit tests — claudony-casehub

**FleetScriptParserTest:**
- Parses full YAML with desiredState, variables, nodes
- Parses minimal YAML (nodes only, no desiredState, no variables)
- Substitutes `${var.xxx}` in string values
- Substitutes in nested maps and lists
- Throws on missing variable reference
- Ignores non-string values during substitution

**FleetScriptRunnerTest (with mock handlers):**
- Executes single node, handler called with correct spec
- Topological sort: A → B → C ordered correctly
- Topological sort: independent nodes (no dependsOn) execute in stable order
- Cycle detection throws with cycle description
- Dependency failure cascades to dependents
- Independent nodes not affected by sibling failure
- Unknown type produces skipped result with warning
- Empty nodes map produces empty result

### Integration tests — claudony-app

**PoolNodeHandlerTest:**
- Maps spec to AgentPoolDefinition correctly
- Registers pool in AgentPoolManagerRegistry
- Handles missing optional fields (scaling defaults to none)

**ChannelNodeHandlerTest:**
- Maps spec to ChannelCreateRequest correctly
- Creates channel in ChannelService

**FleetScriptE2ETest (`@QuarkusTest`):**
- Loads fleet YAML declaring 2 pools + 2 channels with dependencies
- Executes via FleetScriptService
- Verifies pools exist in AgentPoolManagerRegistry with correct min/max
- Verifies channels exist in InMemoryChannelStore with correct semantic
- Verifies dependency ordering: pools created before channels
- Verifies pool sessions pre-warmed to declared minimum

**FleetResourceTest (`@QuarkusTest`):**
- POST /api/claudony/fleet/execute returns 200 with results
- Malformed YAML returns 400

## File Inventory

### claudony-casehub/src/main/java/io/casehub/claudony/casehub/fleet/script/

| File | Type | Purpose |
|------|------|---------|
| FleetScript.java | Record | YAML model |
| FleetNode.java | Record | Node model |
| FleetNodeHandler.java | Interface | SPI |
| NodeResult.java | Record | Per-node result |
| FleetScriptResult.java | Record | Aggregate result |
| FleetScriptParser.java | Class | YAML parse + variable substitution |
| FleetScriptRunner.java | Class | Topo-sort + handler dispatch |

### claudony-app/src/main/java/io/casehub/claudony/server/fleet/

| File | Type | Purpose |
|------|------|---------|
| PoolNodeHandler.java | CDI bean | Pool provisioning handler |
| ChannelNodeHandler.java | CDI bean | Channel creation handler |
| FleetScriptService.java | CDI bean | Runner wrapper |
| FleetResource.java | JAX-RS | REST endpoint |

### Test files

| File | Module | Type |
|------|--------|------|
| FleetScriptParserTest.java | casehub | Unit |
| FleetScriptRunnerTest.java | casehub | Unit |
| PoolNodeHandlerTest.java | app | Unit |
| ChannelNodeHandlerTest.java | app | Unit |
| FleetScriptE2ETest.java | app | QuarkusTest |
| FleetResourceTest.java | app | QuarkusTest |
| test-fleet.yaml | app/test/resources | Test fixture |

## References

- casehubio/claudony#246 — YAML schema source, desiredstate reconciliation design
- casehubio/claudony#205 — fleet manager (pools infrastructure)
- `AgentPoolYamlParser.java` — existing pool YAML parsing
- `AgentPoolDefinitionRegistry.java` — pool definition store
- `AgentPoolManagerRegistry.java` — pool manager store
- `ClaudonyAgentBackend.java` — `fromDefinition()` static method for pool creation
- `ChannelService` (Qhorus) — channel CRUD
- `PoolService.java` — existing pool REST operations at /api/claudony/pools
