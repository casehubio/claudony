# Claudony — Design Snapshot
**Date:** 2026-04-13
**Topic:** Post-launch state — naming, landing page, full internal rename complete
**Supersedes:** *(none — first snapshot)*
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

Claudony is a deployed, working system: a Quarkus binary in two modes (server + agent)
that lets you run Claude Code sessions on one machine and access them from any browser or
PWA. The project has just completed a full rename from its working name "RemoteCC" to its
public name "Claudony" — 100% coverage across Java packages, config properties, runtime
directories, docs, and tests. A public landing page is live at
`https://mdproctor.github.io/claudony/`. 139 tests pass.

## How We Got Here

Key decisions made to reach this point, in rough chronological order.

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Terminal streaming | `tmux pipe-pane` → FIFO → virtual thread → WebSocket | ProcessBuilder can't provide a PTY; tmux manages it instead | pty4j (JNI, incompatible with GraalVM native image) |
| MCP transport | HTTP JSON-RPC POST /mcp | Quarkus is always running; no stdio process spawn needed; GraalVM-compatible | stdio process |
| Authentication | WebAuthn passkey + API key (X-Api-Key header for Agent) | Passkeys are phishing-resistant; API key is simple for machine-to-machine | Password, SSH keys |
| Two-mode binary | Single Quarkus binary, `claudony.mode=server\|agent` | Mac Mini hosts sessions; MacBook has iTerm2 — same binary, different roles | Two separate binaries |
| Native image | GraalVM 25, no JNI libs | Fast startup, low memory, system-tool feel | JVM-only distribution |
| Project name | Claudony (from colony metaphor) | Unique, available, evokes the controller/fleet concept; strong visual identity | RemoteCC (working name), other candidates checked for trademark conflicts |
| Visual identity | Bioluminescent colony — void-black, violet/green/magenta, organic SVG nodes | Colony metaphor maps naturally to sessions as organisms; distinctive from QuarkMind (hex + cyan) | Deep Space Colony (hex grid + cyan — too close to QuarkMind) |
| Landing page | Jekyll 4 in `docs/`, GitHub Pages via Actions | Follows project convention (sparge, cc-praxis); Jekyll 4 needs Actions (classic pins 3.9) | Served from Quarkus app, separate domain |
| Internal rename | Clean cut — no backward compat | Personal tool, no external users; existing tmux sessions and `~/.remotecc/` abandoned | Dual-prefix support, migration script |

## Where We're Going

The app UI (dashboard and terminal view) still uses the VS Code dark theme from the
initial build. A redesign to the bioluminescent colony aesthetic is the logical next
step to match the landing page identity. The xterm.js terminal view needs a separate,
configurable approach — the terminal is a work surface, not a branding surface. Users
have strong preferences (Nord, Solarized, Dracula, etc.).

**Next steps:**
- Dashboard redesign — apply Claudony colony theme to session cards and dashboard chrome
- xterm.js theming — expose configurable presets; don't force the colony palette inside the terminal pane
- Session expiry — sessions are session cookies with no max-age; needs implementation

**Open questions:**
- What's the right xterm.js theming mechanism — URL params, settings UI, or user preference stored in credentials file?
- Should the dashboard theme be user-configurable or fixed to the colony palette?
- When does the binary get a proper CLI wrapper (`claudony server` / `claudony agent`) instead of `-Dclaudony.mode=...`?
- Is Docker-sandboxing per session worth pursuing, or is tmux isolation sufficient for the intended use case?

## Linked ADRs

| ADR | Decision |
|---|---|
| [ADR-0001 — Terminal streaming via pipe-pane + FIFO](../adr/ADR-0001-terminal-streaming-pipe-pane-fifo.md) | No PTY; tmux pipe-pane → FIFO → WebSocket |
| [ADR-0002 — MCP transport HTTP JSON-RPC](../adr/ADR-0002-mcp-transport-http-json-rpc.md) | POST /mcp, not stdio |
| [ADR-0003 — Authentication WebAuthn + API key](../adr/ADR-0003-authentication-webauthn-api-key.md) | Passkey for browser, X-Api-Key for Agent |

## Context Links

- Design doc: [docs/DESIGN.md](../DESIGN.md)
- Landing page spec: [docs/superpowers/specs/2026-04-11-landing-page-design.md](../superpowers/specs/2026-04-11-landing-page-design.md)
- Landing page: https://mdproctor.github.io/claudony/
- Rename PR: mdproctor/claudony#45
- Landing page PR: mdproctor/claudony#43
