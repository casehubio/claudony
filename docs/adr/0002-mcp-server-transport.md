# 0002 — MCP Server Transport

Date: 2026-04-14
Status: Accepted

## Context and Problem Statement

The Claudony Agent exposes an MCP endpoint so a controller Claude instance can
manage sessions. MCP supports two transport options: stdio (subprocess) and
HTTP/SSE. The choice affects deployment model, GraalVM compatibility, and how
Claude Code connects to the Agent.

## Decision Drivers

* Must be GraalVM native image compatible — no subprocess management
* Claude Code must be able to connect to a remote Agent over the network
* Single binary must serve both the MCP endpoint and remain available to restart
  without killing the MCP session
* Quarkus already provides HTTP infrastructure — reuse it

## Considered Options

* **Option A** — stdio transport (MCP default — subprocess spawned by Claude Code)
* **Option B** — HTTP/SSE transport (POST /mcp JSON-RPC endpoint)

## Decision Outcome

Chosen option: **Option B** (HTTP/SSE), because it works over the network,
requires no subprocess management, is GraalVM-native compatible, and reuses
the existing Quarkus HTTP server.

### Positive Consequences

* Claude Code connects to Agent over the network — works across machines
* No process lifecycle coupling: the Agent can restart independently of Claude Code
* Single HTTP server handles both MCP and the REST API
* GraalVM native compatible — no JVM process spawning required

### Negative Consequences / Tradeoffs

* Less common than stdio — requires explicit MCP server configuration in Claude Code
  (`--mcp-server http://localhost:7778/mcp`)
* HTTP adds a small round-trip overhead vs. in-process stdio pipe (negligible in practice)

## Pros and Cons of the Options

### Option A — stdio transport

* ✅ MCP default — zero configuration for Claude Code
* ❌ Requires Claude Code to spawn the Agent as a subprocess
* ❌ Subprocess lifetime tied to Claude Code session
* ❌ Cannot connect to a remote Agent on another machine
* ❌ Subprocess model incompatible with GraalVM native image startup model

### Option B — HTTP/SSE transport (chosen)

* ✅ Network-addressable — works across machines
* ✅ GraalVM native compatible
* ✅ Independent lifecycle from Claude Code
* ❌ Requires explicit URL configuration in Claude Code

## Links

* `src/main/java/dev/claudony/agent/McpServer.java` — implementation
* Quarkiverse `quarkus-mcp-server` extension handles protocol boilerplate
