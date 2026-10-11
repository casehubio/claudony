## D1: Namespace semantics — namespace = app identity

**Choice:** Namespace field on RegistryEntry carries the owning application's identity (e.g. `namespace="scaffold"`, `namespace="trellis"`). Infrastructure entities not owned by any app use `namespace="fleet"`.
**Alternatives:**
- Coarse grouping (namespace="fleet"/"mesh") with ownership via relationships — two-hop queries for the most common operation ("list my pools"), no direct queryability
- Hybrid (namespace for category + metadata.appId for ownership) — duplicates type's categorical role, metadata not queryable via RegistryQuery, structural identity in untyped map
**Rationale:** Three orthogonal queryable dimensions: `type` (what), `namespace` (who owns), `tenancyId` (which tenant). Namespace-as-ownership aligns with every precedent (K8s, DNS, Java packages). `type` already provides categorical distinction, making namespace-as-category redundant. RegistryQuery filters directly on namespace — zero post-filtering needed.
**Trade-offs:** Shared pools (N:M app→pool) can't use namespace alone — must fall back to relationships. Namespace is a flat string — no hierarchical sub-app scoping without adopting Path.
**Sources:** RegistryEntry.java (platform-api), RegistryQuery.java (platform-api), PeerRegistry.java:241 (current namespace="fleet"), AgentPoolDefinitionRegistry.java:88 (current namespace="fleet")
**Exploration:** deep-analysis
**Status:** captured

## D2: Containment hierarchy — namespace for discovery, relationships for lifecycle

**Choice:** Two complementary mechanisms: namespace handles discovery ("find scaffold's pools"), relationships handle lifecycle/cascade ("deregistering scaffold cascades to its pools"). App→pool uses `Relationship(appId, poolId, "owns")` for cascade + `namespace=appName` for queries. Pool→session uses existing `Relationship(poolId, sessionId, "contains")` pattern.
**Depends on:** D1 (namespace = app identity)
**Alternatives:**
- Relationships only (no namespace-based discovery) — contradicts D1; forces two-hop traversal for the most common query ("list my pools")
- Parent ID in metadata — untyped, not queryable via RegistryQuery, duplicates relationship graph
- Namespace only (no relationships) — blocks CascadeRule (#571) which is relationship-driven; no lifecycle propagation path for app deregistration
**Rationale:** Namespace and relationships express different truths about the same reality. Namespace is "this entity's identity is scoped to app X" (queries). Relationship is "this entity has a lifecycle dependency on entity Y" (cascade). They agree because they describe the same ownership from different angles. Registration code writes both atomically.
**Trade-offs:** Two writes per pool registration (register entry + link relationship). Must keep namespace and relationship consistent — but both are written in the same code path (pool creation), so consistency is straightforward.
**Sources:** PoolMeshRegistrar.java (existing pool→session relationship pattern), CascadeRule.java (platform-api, relationship-driven cascade), platform#571 (cascade enforcement issue)
**Exploration:** deep-analysis
**Status:** captured

## D3: Capacity model — app declares pools at registration

**Choice:** Application registration includes pool specifications (agentId, minActive, maxActive). Claudony creates app-namespaced pools from these specs. Fleet-level budget limits total capacity across all apps.
**Depends on:** D1 (namespace = app identity), D2 (app→pool relationship for cascade)
**Alternatives:**
- Fleet allocates on request — app registers empty, then negotiates capacity via separate API. Adds a negotiation protocol and statefulness beyond what's needed now.
- Shared pools with app quotas — pools are fleet-level, apps get session quotas. Maximises utilisation but contradicts D1 (pool namespace would be "fleet", not app-scoped) and complicates isolation.
**Rationale:** Aligns with existing AgentPoolDefinition model (minActive, maxActive per pool). Declarative — app says what it needs at registration time. Fleet budget enforcement already exists (BudgetConfig on AgentPoolDefinition). Per-app pools provide clean isolation and namespace scoping.
**Trade-offs:** Lower utilisation than shared pools (app pools may sit idle). Doesn't support dynamic scaling requests after registration without updating the app spec.
**Sources:** AgentPoolDefinition.java (existing pool config model), BudgetConfig.java (existing budget enforcement), ScalingScheduler.java (existing scaling)
**Exploration:** quick
**Status:** captured

## D4: Channel scoping — prefix convention

**Choice:** App channels use prefix `app:{namespace}/{purpose}` — e.g. `app:scaffold/work`, `app:scaffold/observe`. Consistent with existing Qhorus channel prefix ownership model.
**Depends on:** D1 (namespace = app identity)
**Alternatives:**
- Defer channel scoping — keeps #269 scope smaller but leaves an unaddressed gap in the namespace model
**Rationale:** Existing channel namespace conventions (case-{uuid}/, team/, life/, issue/, collab/) already use prefix ownership for scoping. App channels follow the same pattern with no Qhorus API changes. Channel discovery via `findByNamePrefix("app:scaffold/")`.
**Trade-offs:** Channel names carry the app identity as a string convention rather than a typed field — relies on naming discipline. But this is the established pattern across all Qhorus channel types.
**Sources:** docs/CHANNELS.md (prefix ownership conventions), ClaudonyCaseChannelProvider.java (existing case- prefix pattern)
**Exploration:** quick
**Status:** captured

## D5: API surface — full stack with @McpDomain

**Choice:** Implement both the service layer (ApplicationRegistrationService in claudony-casehub) and the @McpDomain API class (in claudony-app) in #269. @McpDomain gives REST + MCP + GraphQL from one annotated class.
**Depends on:** D3 (app declares pools at registration)
**Alternatives:**
- Service first, @McpDomain later — smaller scope but defers the API surface that consumers need. Not aligned with the "full stack in one issue" preference.
**Rationale:** The @McpDomain pattern means all three API surfaces (REST, MCP, GraphQL) come from one class. No reason to split. Service + API together enables end-to-end testing and gives immediate consumer value.
**Trade-offs:** Larger issue scope. But the @McpDomain class is thin — it delegates to the service, so the incremental effort is small.
**Sources:** ClaudonyFleetApi.java (existing @McpDomain pattern), ClaudonyPoolApi.java (existing @McpDomain pattern)
**Exploration:** quick
**Status:** captured

## D6: Application metadata — minimal: name + capabilities

**Choice:** Application RegistryEntry metadata carries displayName, description, and requested capabilities (comma-separated agent types). Pool specifications are separate objects passed to the registration service, not encoded in the metadata map.
**Depends on:** D3 (app declares pools at registration)
**Alternatives:**
- Rich metadata (owner, version, status) — some fields (ownerActorId, version) belong in a higher-level app model, not registry metadata. Over-loading metadata with structured data invites the same problem D1 rejected (structural identity in untyped maps).
**Rationale:** RegistryEntry metadata is for extensible, non-structural properties. App display name and description fit this — they're informational, not structural. Capabilities as a comma-separated string enables simple filtering. Pool specs are structured data with their own types (AgentPoolDefinition) and shouldn't be flattened into metadata.
**Trade-offs:** No app versioning or owner tracking in the registry. If needed later, a dedicated ApplicationDefinition record can be introduced alongside the RegistryEntry.
**Sources:** RegistryEntry.java (metadata: Map<String, String>), AgentPoolDefinition.java (structured pool config)
**Exploration:** quick
**Status:** captured
