# RemoteCC — Design Snapshot

**Date:** 2026-04-06
**Topic:** Full system state — post-blog-catchup
**Supersedes:** [2026-04-06-full-system-state](2026-04-06-full-system-state.md)
**Superseded by:** [2026-04-06-post-auth-hardening](2026-04-06-post-auth-hardening.md)

---

## Where We Are

RemoteCC is a working system. A Quarkus binary in two modes — Server and Agent — lets you run Claude Code CLI sessions on a Mac and access them from any browser or PWA. The Server manages tmux sessions, streams terminal output over WebSocket via pipe-pane + FIFO, and serves the web dashboard. The Agent exposes a JSON-RPC MCP endpoint so a controller Claude instance can manage sessions. Authentication is implemented: WebAuthn passkeys for browsers, API key for the Agent. 106 tests pass. The end-to-end chain — real `claude` CLI → MCP → REST → tmux — has been validated. The blog is caught up through this session.

## How We Got Here

Key decisions made to reach this point.

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Terminal streaming | `tmux pipe-pane` + named FIFO | ProcessBuilder cannot provide a PTY; pipe-pane works headless | `tmux attach-session` (requires PTY — fails in JVM) |
| History replay on reconnect | `tmux capture-pane -e -p -S -100` sent synchronously before pipe-pane | Avoids race condition; ANSI colour preserved | Replay after pipe-pane starts (race condition corrupted first char) |
| TUI redraw on connect | `tmux resize-pane` to browser dimensions before pipe-pane starts | Delivers SIGWINCH; TUI redraws into live stream before user sees anything | Manual user resize (garbled until user acts) |
| MCP transport | HTTP JSON-RPC (`POST /mcp`) | GraalVM-native compatible; no stdio process needed | SSE, stdio MCP (incompatible with native or headless) |
| Browser auth | WebAuthn passkeys via `quarkus-security-webauthn` | No password to leak; Touch ID UX; invite-based onboarding | Passwords (extra secret to manage), basic auth (weak) |
| Agent→Server auth | `X-Api-Key` header checked by `ApiKeyAuthMechanism` | Simple, stateless, fits headless Agent | Session cookies (require browser-style session management) |
| Credential storage | JSON file at `~/.remotecc/credentials.json` (atomic write, `rw-------`) | Self-contained, no database dependency | Database, system keychain |
| Directory convention | `~/.remotecc/` config, `~/remotecc-workspace/` sessions | Hidden dot-dir for system state; visible dir for user work | Single directory (mixes credentials with user files) |
| E2E test strategy | Side-effect assertions (tmux state) not LLM output | LLM output is non-deterministic; tmux state is not | Assert on Claude's words (fragile across model versions) |
| `--mcp-config` format | `mcpServers` wrapper required (same schema as `settings.json`) | Discovered via failed run; some plugin examples misleadingly omit it | Top-level server names (produces schema validation error) |

## Where We're Going

**Next steps:**
- Production-harden auth: rate limiting, session expiry, dev quick-login removal before deployment
- Mac Mini deployment: bind to `0.0.0.0`, configure as launchd service, set real WebAuthn RP ID and origins
- Verify native binary still compiles cleanly with `quarkus-security-webauthn` included

**Open questions:**
- Should the credential store eventually support multiple named users, or remain single-owner?
- How should the Agent API key be provisioned on a fresh Mac Mini — environment variable, config file, or first-run setup wizard?
- Is there value in a Docker sandbox per session for isolation, or is tmux namespacing sufficient for personal use?
- Does the native binary still compile cleanly now that `quarkus-security-webauthn` is included? (not verified since auth was added)

## Linked ADRs

*(No ADRs created yet — the decision table above captures the key choices. ADRs should be created for terminal streaming, MCP transport, and auth mechanism before 1.0.)*

## Context Links

- Original design spec: [`docs/superpowers/specs/2026-04-03-remotecc-design.md`](superpowers/specs/2026-04-03-remotecc-design.md)
- Auth design spec: [`docs/superpowers/specs/2026-04-05-auth-design.md`](superpowers/specs/2026-04-05-auth-design.md)
- E2E testing design: [`docs/superpowers/specs/2026-04-05-e2e-testing-design.md`](superpowers/specs/2026-04-05-e2e-testing-design.md)
- Blog entries: [`docs/blog/`](../blog/)
