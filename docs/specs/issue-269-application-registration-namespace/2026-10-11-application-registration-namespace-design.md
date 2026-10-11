# Application Registration + Namespace Model — Stage 3a

**Issue:** casehubio/claudony#269
**Parent:** casehubio/claudony#267 (modular fleet architecture epic)
**Date:** 2026-10-11

---

## Problem

The registry stores peers, pools, and agent instances — but there's no concept of an "application" as a first-class entity. Scaffold, trellis, or any CaseHub app that wants fleet capacity has no way to register, get namespaced resources, or be discovered. The `namespace` field on `RegistryEntry` is hardcoded to `"fleet"` everywhere, serving no purpose.

## Solution Overview

Define application registration as a registry entity with namespace-based isolation. Three key changes:

1. **Namespace = app identity** — the `namespace` field on `RegistryEntry` carries the owning application's identity. Infrastructure entities use `namespace="fleet"`.
2. **Containment hierarchy** — `application → pool → session` with namespace for discovery and relationships for lifecycle cascade.
3. **Declarative pool specs** — apps declare their pool requirements at registration time.

---

## Design

### Entity Types in the Registry

After this change, the registry contains four entity types:

| Type | Namespace | Example ID | Registered by |
|------|-----------|------------|---------------|
| `application` | app name (e.g. `scaffold`) | `scaffold` | ApplicationRegistrationService |
| `pool` | owning app name | `scaffold/code-review` | ApplicationRegistrationService (on app register) |
| `node` | `fleet` | `peer-uuid` | PeerRegistry (existing) |
| `session` | owning app name | `session-uuid` | PoolMeshRegistrar (existing, updated for namespace) |

Fleet infrastructure (peers/nodes) keeps `namespace="fleet"` — the fleet itself is the owner of infrastructure entities.

### Namespace Semantics

Three orthogonal, queryable dimensions on every `RegistryEntry`:

| Field | Semantic | Example values |
|-------|----------|---------------|
| `type` | What this entity IS | `node`, `pool`, `session`, `application` |
| `namespace` | Who this entity BELONGS TO | `fleet`, `scaffold`, `trellis` |
| `tenancyId` | Which tenant this is in | `default`, `acme-corp` |

Common queries become single `RegistryQuery` calls:
- "All of scaffold's resources" → `RegistryQuery(tenancyId, type=null, namespace="scaffold")`
- "All pools across all apps" → `RegistryQuery(tenancyId, type="pool", namespace=null)`
- "Scaffold's pools" → `RegistryQuery(tenancyId, type="pool", namespace="scaffold")`
- "All registered apps" → `RegistryQuery(tenancyId, type="application", namespace=null)`

### Containment Hierarchy

Two complementary mechanisms — namespace for discovery, relationships for lifecycle:

```
application ("scaffold")
   │
   ├── namespace="scaffold" ← discovery (RegistryQuery)
   ├── Relationship("scaffold", "scaffold/code-review", "owns") ← lifecycle/cascade
   │
   └── pool ("scaffold/code-review")
          │
          ├── namespace="scaffold" ← discovery
          ├── Relationship("scaffold/code-review", "session-uuid", "contains") ← lifecycle/cascade (existing pattern)
          │
          └── session ("session-uuid")
                 └── namespace="scaffold" ← discovery
```

**Why both mechanisms:**
- **Namespace** answers "whose stuff is this?" — used by `RegistryQuery.discover()` for fast, indexed lookups.
- **Relationships** answer "what depends on what?" — used by `CascadeRule` (#571) for lifecycle propagation when an entity is deregistered.

They express the same ownership from different angles. Registration code writes both atomically.

**Relationship types:**
- `"owns"` — application → pool (new)
- `"contains"` — pool → session (existing, from PoolMeshRegistrar)

### Application Registration Model

#### ApplicationSpec — the registration request

```java
public record ApplicationSpec(
    String name,              // unique app identifier, becomes the namespace
    String displayName,       // human-readable name
    String description,       // what this app does
    List<PoolSpec> pools      // requested pool capacity
) {
    public record PoolSpec(
        String agentId,       // agent type (e.g. "code-review", "research")
        int minActive,        // pre-warm count
        int maxActive         // capacity ceiling
    ) {}
}
```

The `name` field becomes:
- The `id` of the application's `RegistryEntry`
- The `namespace` for all entities belonging to this app
- The prefix for pool IDs: `{name}/{agentId}` (e.g. `scaffold/code-review`)

#### ApplicationInfo — the query response

```java
public record ApplicationInfo(
    String name,
    String displayName,
    String description,
    HealthStatus health,
    Instant registeredAt,
    List<PoolSummary> pools
) {}
```

### ApplicationRegistrationService

Lives in `claudony-casehub` module. Orchestrates registration across `RegistryService` and `AgentPoolDefinitionRegistry`.

**Registration flow:**

1. Validate `ApplicationSpec` (name uniqueness, pool spec constraints)
2. Create application `RegistryEntry`:
   ```java
   new RegistryEntry(
       spec.name(),                              // id
       "application",                            // type
       spec.name(),                              // namespace = app identity
       tenancyId,                                // from TenantContext
       Map.of("displayName", spec.displayName(),
              "description", spec.description()),
       Instant.now(), Instant.now(),
       Duration.ofHours(24),                     // TTL — apps heartbeat to stay alive
       HealthStatus.HEALTHY
   )
   ```
3. For each `PoolSpec`, create an `AgentPoolDefinition` with the app namespace:
   - Pool name: `{appName}/{agentId}` (e.g. `scaffold/code-review`)
   - Register in `AgentPoolDefinitionRegistry` (which writes to `RegistryService` with `namespace=appName`)
4. Link application → each pool: `registry.link(new Relationship(appName, poolId, "owns"))`

**Deregistration flow:**

1. Deregister the application entry from `RegistryService`
2. Cascade will be handled by #571 — for now, explicitly deregister pools and unlink relationships
3. Pool deregistration cascades to sessions via existing `"contains"` relationships

**Key methods:**

```java
public class ApplicationRegistrationService {
    ApplicationInfo register(ApplicationSpec spec);
    void deregister(String appName);
    Optional<ApplicationInfo> get(String appName);
    List<ApplicationInfo> list();
    void heartbeat(String appName);
}
```

### @McpDomain API

Lives in `claudony-app` module. Thin delegation to `ApplicationRegistrationService`.

```java
@McpDomain(value = "claudony/apps", app = "claudony",
           basePath = "/api/claudony/apps",
           summary = "Application registration — namespace, pools, lifecycle")
@ApplicationScoped
public class ClaudonyAppApi {

    @Inject ApplicationRegistrationService appService;

    @PlatformQuery("List registered applications")
    @RestPath("/")
    List<ApplicationInfo> listApps();

    @PlatformQuery("Get application details")
    @RestPath("/{name}")
    ApplicationInfo getApp(@PathParam String name);

    @PlatformMutation("Register an application")
    @RestPath("/")
    ApplicationInfo registerApp(ApplicationSpec spec);

    @PlatformMutation("Deregister an application")
    @RestPath("/{name}")
    void deregisterApp(@PathParam String name);

    @PlatformMutation("Heartbeat an application")
    @RestPath("/{name}/heartbeat")
    void heartbeat(@PathParam String name);
}
```

### Channel Scoping

App channels use prefix convention `app:{namespace}/{purpose}`:

- `app:scaffold/work` — scaffold's work channel
- `app:scaffold/observe` — scaffold's observation channel

Consistent with existing Qhorus channel prefix ownership model (docs/CHANNELS.md). Channel discovery via `findByNamePrefix("app:scaffold/")`. No Qhorus API changes needed.

Channel creation is not part of the registration flow — apps create channels on demand via existing Qhorus `ChannelService`. The naming convention ensures isolation.

### Namespace Isolation Enforcement

Namespace isolation is **advisory in Stage 3a**, not enforced. Apps can query any namespace via `RegistryQuery`. Hard enforcement (RBAC-gated namespace access) is a follow-on concern when `casehub-platform-oidc` is wired into the registry.

What Stage 3a provides:
- Clear namespace boundaries — each app's entities are discoverable by namespace
- Pool ID uniqueness scoped to namespace — `scaffold/code-review` and `trellis/code-review` are distinct pools
- Channel prefix convention — `app:scaffold/` channels are distinct from `app:trellis/`

### Impact on Existing Code

**PeerRegistry** — no change. Peers continue to register with `namespace="fleet"`, `type="node"`.

**AgentPoolDefinitionRegistry** — `toRegistryEntry()` currently hardcodes `namespace="fleet"`. Two changes: (1) Add a `namespace` field to `AgentPoolDefinition` (or pass it as a parameter to `register()`). (2) `toRegistryEntry()` reads the namespace from the definition instead of hardcoding. For fleet-level pools (no owning app), namespace remains `"fleet"` — callers that don't set a namespace get the default. The existing `register(AgentPoolDefinition)` method signature gains a `String namespace` parameter; the single-arg overload defaults to `"fleet"` for backward compatibility.

**PoolMeshRegistrar** — sessions linked to pools via `"contains"` relationship continue as-is. Session `RegistryEntry` instances now carry the owning app's namespace (resolved from the pool's namespace).

---

## Test Plan

All tests against `InMemoryRegistryService` — no database required.

### ApplicationRegistrationService tests (claudony-casehub)

- `register_createsAppEntryWithCorrectTypeAndNamespace` — verify type="application", namespace=appName
- `register_createsPoolsWithAppNamespace` — verify pools registered with namespace=appName
- `register_linksAppToPoolsWithOwnsRelationship` — verify Relationship(appId, poolId, "owns") created
- `register_poolIdIncludesAppPrefix` — verify pool ID is `{appName}/{agentId}`
- `register_duplicateNameThrows` — verify IllegalStateException on duplicate app name
- `deregister_removesAppAndPools` — verify all entries and relationships cleaned up
- `deregister_unknownAppNoOp` — verify no exception for unknown app name
- `get_returnsAppWithPools` — verify ApplicationInfo includes pool summaries
- `list_returnsAllApps` — verify multiple registered apps returned
- `heartbeat_updatesAppEntry` — verify lastHeartbeat updated
- `register_multipleAppsIsolatedByNamespace` — verify discover(namespace=A) returns only A's entities

### ClaudonyAppApi tests (claudony-app, @QuarkusTest)

- `listApps_empty` — 200, empty list
- `registerApp_success` — 200, returns ApplicationInfo with pools
- `getApp_notFound` — 404 for unknown app
- `deregisterApp_success` — deregisters and cascades to pools
- `heartbeat_success` — 200 for registered app

### AgentPoolDefinitionRegistry update tests

- `register_withNamespace_usesProvidedNamespace` — verify namespace propagated to RegistryEntry
- `register_withoutNamespace_defaultsToFleet` — backward compatibility

---

## Module Placement

| Component | Module | Rationale |
|-----------|--------|-----------|
| `ApplicationSpec` | `claudony-casehub` | Registration model, consumed by service and API |
| `ApplicationInfo` | `claudony-casehub` | Query response model |
| `ApplicationRegistrationService` | `claudony-casehub` | Orchestrates registration across registry + pool definition |
| `ClaudonyAppApi` | `claudony-app` | @McpDomain API class — REST + MCP + GraphQL |

Both records and the service live in `claudony-casehub` (Tier 2) because they coordinate registry and pool infrastructure. The API class lives in `claudony-app` (Tier 3) following the existing pattern for `@McpDomain` classes.

---

## Downstream Impact

- **#571 (cascade enforcement):** Application→pool `"owns"` relationship is the first real consumer of cascade. Deregistering an app should cascade-deregister its pools (which cascade-deregister their sessions).
- **#570 (registry-client):** Remote apps use `registry-client` to call the REST endpoints exposed by `ClaudonyAppApi`.
- **Existing pools:** Fleet-level pools (no owning app) continue to work with `namespace="fleet"`. No migration needed — existing pools are infrastructure, not app-owned.

---

## References

- `RegistryEntry.java`, `RegistryService.java`, `RegistryQuery.java` (platform-api) — registry SPI
- `Relationship.java`, `CascadeRule.java`, `CascadeAction.java` (platform-api) — relationship and cascade model
- `InMemoryRegistryService.java` (platform-registry-inmem-core) — test backend
- `PeerRegistry.java` (claudony-app) — existing type="node" registration with namespace="fleet"
- `AgentPoolDefinitionRegistry.java` (claudony-casehub) — existing type="pool" registration
- `PoolMeshRegistrar.java` (claudony-app) — existing pool→session "contains" relationship pattern
- `ClaudonyPoolApi.java` (claudony-app) — existing @McpDomain pattern
- `AgentPoolDefinition.java` (claudony-casehub) — pool spec model
- `docs/CHANNELS.md` — channel prefix ownership conventions
- casehubio/platform#571 — cascade enforcement (downstream)
- casehubio/platform#570 — registry-client (downstream)
- casehubio/claudony#267 — parent epic
