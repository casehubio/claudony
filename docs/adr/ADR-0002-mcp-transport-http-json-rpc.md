# ADR-0002: MCP Transport via HTTP JSON-RPC

**Status:** Accepted  
**Date:** 2026-04-04  
**Deciders:** Mark Proctor

---

## Context

The Agent mode exposes an MCP (Model Context Protocol) endpoint so a controller Claude Code instance can manage RemoteCC sessions through conversation. MCP supports multiple transport mechanisms; we needed to choose one that works with:

1. **GraalVM native image.** The Agent binary is compiled to native. Transport mechanisms requiring a subprocess (stdio) or dynamic class loading are problematic.
2. **Quarkus always running.** The Agent is a long-running service, not a command invoked on demand. It starts once and handles many requests over its lifetime.
3. **HTTP already available.** Quarkus exposes HTTP; adding an MCP endpoint is zero additional infrastructure.

---

## Alternatives Considered

### 1. stdio transport
The most common MCP transport: the host (Claude Code) spawns a subprocess and communicates over stdin/stdout. This is how most existing tmux MCP tools work (conductor-mcp, tmux-claude-mcp-server).

**Rejected.** The Agent is a persistent Quarkus service, not a command. Stdio transport requires Claude Code to spawn the Agent as a child process, which is incompatible with running it as a native binary service that manages its own lifecycle. Also, a subprocess spawned per-conversation would lose in-memory state.

### 2. SSE transport (HTTP + Server-Sent Events)
MCP's streaming transport: client POSTs to an endpoint, server streams responses as SSE events.

**Considered but not needed.** SSE is useful for streaming long-running tool responses. RemoteCC's 8 tools all complete synchronously (tmux commands finish quickly). Synchronous HTTP is simpler and sufficient.

### 3. HTTP JSON-RPC (synchronous POST) — chosen
Expose `POST /mcp` as a synchronous JSON-RPC endpoint. Claude Code connects via `--mcp-config` specifying the URL.

**Accepted.**

---

## Decision

The Agent exposes `POST /mcp` as a synchronous JSON-RPC 2.0 endpoint. Each MCP method (initialize, tools/list, tools/call) is handled in a single request-response cycle. The endpoint requires no authentication — the Agent runs on localhost and the Server validates the API key separately.

**Config format:** The Claude Code MCP config file requires a `mcpServers` wrapper key (matches the `settings.json` schema). Without this wrapper, `tools/list` returns empty even though the server starts successfully.

---

## Consequences

**Positive:**
- Zero additional infrastructure — HTTP is already running
- GraalVM native compatible — no subprocess, no dynamic class loading
- Simple to test (plain HTTP calls, no process management)
- Claude Code's `--mcp-config` points directly to the running Agent URL

**Negative:**
- No streaming responses — tool results must fit in a single JSON response body (acceptable for all current tools)
- If the Agent is not running when Claude Code starts, the MCP tools are unavailable (no reconnection)
- The `mcpServers` wrapper requirement is undocumented and only discoverable by running the real CLI

**See also:** `docs/ideas/IDEAS.md` — stdio transport noted as a future consideration if needed for compatibility with other Claude clients
