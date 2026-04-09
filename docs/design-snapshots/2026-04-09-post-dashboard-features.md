# RemoteCC — Design Snapshot
**Date:** 2026-04-09
**Topic:** Post-dashboard-features — PR/CI badges, service health, compose overlay
**Supersedes:** [2026-04-07-post-api-key-provisioning](2026-04-07-post-api-key-provisioning.md)
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

RemoteCC is fully working end-to-end with auth, terminal streaming, and a
feature-rich dashboard. The session card now shows GitHub PR/CI status and
live service health badges on demand. A compose overlay in the terminal view
provides full browser text editing for Claude Code's prompt — working around
the upstream Ink input component's missing click-to-position support.
139 tests passing. Running in JVM mode with stable session encryption.

## How We Got Here

Key decisions made to reach this point, in rough chronological order.

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| PR/CI status via on-demand button | Fetch on click, not auto-poll | Avoids GitHub API rate limits; keeps dashboard lightweight | Auto-refresh every 5s |
| Service health via TCP connect | `Socket.connect()` with 500ms timeout, parallel virtual threads | Works for any service (not just HTTP); fast | HTTP GET (HTTP-only, more complex) |
| Compose sends via `terminal.paste()` | xterm.js API method | Routes through AttachAddon pipeline; handles bracketed paste mode correctly | `ws.send()` — bypasses AttachAddon, breaks on reconnect |
| Compose inserts at cursor position | No pre-clearing | Matches text editor behaviour; cursor-aware insert is more useful | Always clear prompt first |
| iTerm2 button localhost-only | Check `window.location.hostname` | iTerm2 only works when server is co-located with the browser | Show always (broken on mini PC) |
| Stable session encryption key | `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` env var | Prevents auth cookie invalidation on server restart | Random key per restart (Quarkus default) |

## Where We're Going

**Next steps:**
- Deploy to mini PC (headless, no iTerm2) — write `docs/DEPLOYMENT.md`
- Session expiry — `Max-Age` cookies (currently session-scoped, expire on browser close)
- Auto-naming sessions via Claude API
- Lifecycle hooks — scripts on session create/delete

**Open questions:**
- Session expiry: `Max-Age` requires intercepting Quarkus internal cookie issuance — is this worth doing vs accepting browser-close expiry?
- Mini PC deployment: will `gh` CLI be available for PR/CI status? May need setup documentation
- Service health ports: should the checked port list be configurable per session, or is the fixed default set sufficient?

## Linked ADRs

| ADR | Decision |
|---|---|
| [ADR-0001](../adr/ADR-0001-terminal-streaming-pipe-pane-fifo.md) | PTY-free terminal streaming via pipe-pane + FIFO |
| [ADR-0002](../adr/ADR-0002-mcp-transport-http-json-rpc.md) | MCP transport via HTTP JSON-RPC |
| [ADR-0003](../adr/ADR-0003-authentication-webauthn-api-key.md) | Authentication via WebAuthn passkeys + API key |

## Context Links

- Issues closed this session: #34, #35, #36, #37, #38, #39, #40, #41
- Garden submissions: GE-0094–GE-0095 (previous session), GE-0125–GE-0128 (this session)
