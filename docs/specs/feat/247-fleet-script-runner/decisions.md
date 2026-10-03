## D1: Agent node handling

**Choice:** Skip with warning log
**Alternatives:**
- Fail on unknown type — too strict for a runner that's pool+channel focused
- SPI handler with no-op — over-engineered for what's effectively "ignore this"
**Rationale:** Eidos isn't on Claudony's classpath. Agent identity registration is an ops concern. The runner should focus on what it can provision (pools, channels) and gracefully skip what it can't.
**Trade-offs:** If someone passes a fleet script expecting agent registration, they'll only see a warning, not a failure.
**Sources:** pom.xml (no eidos dependency), issue #247 body ("when on classpath or skip")
**Exploration:** quick
**Status:** captured

## D2: Module placement — split for reuse

**Choice:** Core framework in claudony-casehub, concrete handlers + REST in claudony-app
**Alternatives:**
- Everything in claudony-app — simpler but not reusable outside the app module
- New claudony-fleet module — clean boundary but premature for a one-shot runner
**Rationale:** The YAML model, topo-sort, handler dispatch, and variable substitution are general-purpose. The concrete handlers (pool, channel) need app-level dependencies (ClaudonyAgentBackend, ChannelService). Splitting lets other modules or projects reuse the runner framework.
**Trade-offs:** Slightly more wiring than a single-module approach. FleetNodeHandler SPI needs to be in casehub module.
**Sources:** AgentPoolYamlParser.java (already in casehub), ClaudonyAgentBackend.java (in app), ChannelService (Qhorus dep)
**Exploration:** quick
**Status:** captured

## D3: Entry point — CDI bean + REST endpoint

**Choice:** CDI bean + REST endpoint at /api/claudony/fleet/execute (mcpDomain)
**Alternatives:**
- CDI bean only — no REST surface, harder to trigger from MCP or external tools
- CLI command + CDI bean — more machinery for what's a one-shot operation
**Rationale:** REST endpoint makes the runner callable from MCP, curl, or the dashboard. CDI bean makes it injectable for tests. mcpDomain path (/api/claudony/) is consistent with pools API.
**Trade-offs:** REST surface needs auth. Adds one more endpoint to secure.
**Sources:** PoolResource at /api/claudony/pools, ClaudonyPoolApi
**Exploration:** quick
**Status:** captured

## D4: YAML format — full #246 schema

**Choice:** Accept the full #246 YAML format (desiredState + variables + nodes)
**Alternatives:**
- Minimal subset (variables + nodes only) — simpler but needs editing to promote to ops reconciliation
**Rationale:** Zero rewriting path from standalone script to desiredstate reconciliation. Runner ignores desiredState metadata fields (namespace, name) but parses the same structure.
**Trade-offs:** Runner carries fields it doesn't use. Minimal overhead.
**Sources:** Issue #246 YAML example, issue #247 ("same YAML schema")
**Exploration:** quick
**Status:** captured
