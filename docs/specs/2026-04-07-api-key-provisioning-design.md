# API Key Provisioning — Design Spec

**Date:** 2026-04-07  
**Status:** Approved  
**Feature:** First-run wizard for `remotecc.agent.api-key`

---

## Problem

`remotecc.agent.api-key` must be set on both the server and the agent for the agent to authenticate with the server. Currently there is no default, no generation, and no guidance — the user must manually create a secret and configure it in two places before anything works. This is the last deployment blocker before first real use on Mac Mini.

---

## Approach

`ApiKeyService` — a new `@ApplicationScoped` bean that owns all key resolution logic at runtime. It is the single source of truth for the resolved key; `ApiKeyAuthMechanism` and `ApiKeyClientFilter` inject it instead of reading `RemoteCCConfig.agentApiKey()` directly.

**Rejected alternatives (TODOs for future consideration):**
- Env var in launchd plist (`REMOTECC_AGENT_API_KEY`) — no file needed, suitable for production macOS service deployment; simpler but opaque
- Interactive stdin prompt — asks user to enter a key or press Enter to generate; useful for headless setup scripts

---

## Resolution Order

Same on both server and agent:

1. `remotecc.agent.api-key` from Quarkus config (env var `REMOTECC_AGENT_API_KEY` or properties file) — explicit config always wins
2. `~/.remotecc/api-key` file — auto-discovery for same-machine co-location
3. Absent — server generates and persists; agent warns and starts degraded

---

## ApiKeyService

```
ApiKeyService (@ApplicationScoped)
  ├── initServer()         — called from ServerStartup after ensureDirectories()
  ├── initAgent()          — called from AgentStartup before checkServerConnectivity()
  ├── getKey()             — returns Optional<String> cached after init
  ├── loadFromConfig()     — checks RemoteCCConfig.agentApiKey()
  ├── loadFromFile()       — reads ~/.remotecc/api-key if present (trimmed)
  └── generateAndPersist() — server only: generate, write file rw-------, log banner
```

`getKey()` is safe to call at any time after startup. It returns `Optional.empty()` on the agent side if no key was found (degraded mode).

---

## Key Format and File

**Format:** `remotecc-` + UUID without dashes  
Example: `remotecc-550e8400e29b41d4a716446655440000`

**File:** `~/.remotecc/api-key`  
- Plain text, key only, no trailing newline issues (trimmed on read)
- Permissions: `rw-------` (600) set via `PosixFilePermissions` immediately after write
- Directory `~/.remotecc/` is guaranteed to exist before `initServer()` is called (created by `ServerStartup.ensureDirectories()`)

---

## Startup Integration

### Server (`ServerStartup.onStart`)

```
checkTmux()
ensureDirectories()     ← already exists; creates ~/.remotecc/
initServer()            ← NEW: resolve or generate, log banner if generated
bootstrapRegistry()
```

If `~/.remotecc/api-key` already exists, the server loads it silently (no banner). The banner only fires on first generation.

### Agent (`AgentStartup.onStart`)

```
initAgentApiKey()           ← NEW: resolve from config or file, warn banner if absent
checkServerConnectivity()
detectTerminalAdapter()
reportClipboardStatus()
```

If the key file exists (same-machine case), it is loaded silently and used automatically — no user action needed.

---

## Log Output

### Server — first run (key generated), logged at WARN

```
================================================================
REMOTECC — API Key Generated (first run)
  Key:      remotecc-550e8400e29b41d4a716446655440000
  Saved to: /Users/you/.remotecc/api-key

  Same machine (agent + server co-located): no action needed.
  Different machine: configure the agent with —
    export REMOTECC_AGENT_API_KEY=remotecc-550e8400e29b41d4a716446655440000
  or in agent config:
    remotecc.agent.api-key=remotecc-550e8400e29b41d4a716446655440000
================================================================
```

### Agent — no key found, logged at WARN

```
================================================================
REMOTECC AGENT — No API Key Configured
  MCP tools will return 401 until a key is set.
  Copy the key from the server's ~/.remotecc/api-key and set:
    export REMOTECC_AGENT_API_KEY=<key>
  or in agent config:
    remotecc.agent.api-key=<key>
================================================================
```

The agent starts and remains running in degraded mode — MCP tool calls will fail with 401 until the key is configured and the agent is restarted.

---

## Auth Mechanism Changes

`ApiKeyAuthMechanism` currently reads `config.agentApiKey()` directly. After this change it injects `ApiKeyService` and calls `apiKeyService.getKey()`. The comparison logic (constant-time `MessageDigest.isEqual`) is unchanged.

`ApiKeyClientFilter` similarly switches from `config.agentApiKey().ifPresent(...)` to `apiKeyService.getKey().ifPresent(...)`.

`RemoteCCConfig.agentApiKey()` remains in the config interface — it is still the highest-priority input source.

---

## Testing

`ApiKeyServiceTest` — unit test, no `@QuarkusTest`, temp directory injected via constructor:

| Scenario | Assertion |
|---|---|
| Config empty, no file | Key generated, file written, format matches `remotecc-[a-f0-9]{32}` |
| Config empty, file present | File value loaded, no generation |
| Config set, file present | Config value wins |
| File written with correct permissions | `rw-------` (POSIX 600) |
| Agent init, no key anywhere | `getKey()` returns `Optional.empty()` |
| Agent init, file present | `getKey()` returns file value |

`ApiKeyAuthMechanismTest` may need a test key configured. Because `ApiKeyAuthMechanism` will now call `apiKeyService.getKey()` instead of `config.agentApiKey()`, the `@QuarkusTest` context must have `ApiKeyService` initialized with a non-empty key for key-validation tests to work. If `remotecc.agent.api-key` is already set in the test profile, `ApiKeyService.loadFromConfig()` will pick it up automatically and no further changes are needed. If not, the test will need a test property added.

---

## Files Changed

| File | Change |
|---|---|
| `server/auth/ApiKeyService.java` | New — key resolution, generation, persistence |
| `server/ServerStartup.java` | Inject `ApiKeyService`, call `initServer()` after `ensureDirectories()` |
| `agent/AgentStartup.java` | Inject `ApiKeyService`, call `initAgent()` first |
| `server/auth/ApiKeyAuthMechanism.java` | Inject `ApiKeyService`, replace `config.agentApiKey()` |
| `agent/ApiKeyClientFilter.java` | Inject `ApiKeyService`, replace `config.agentApiKey()` |
| `test/.../ApiKeyServiceTest.java` | New unit test |
