# Handover — 2026-04-05

**Head commit:** `5d959a8` — docs: update CLAUDE.md — 81 tests, -Pe2e profile, E2E validated
**Previous handover:** `docs/SESSION-HANDOFF-2026-04-04.md` (old format, pre-skill)

## What Changed This Session

- **Bug fixed:** `TmuxService.sendKeys()` now uses `-l` flag — tmux key names like `"Escape"` were silently firing keypresses instead of sending literal text
- **17 new tests added** (64 → 81): expanded TmuxServiceTest, SessionInputOutputTest, SessionResourceTest, McpServerTest, McpServerIntegrationTest, TerminalWebSocketTest, ServerStartupTest, plus new `ClaudeE2ETest`
- **`-Pe2e` Maven profile** — runs real `claude` CLI against MCP server; excluded from default `mvn test`
- **End-to-end validated** — real `claude` creates tmux sessions via MCP; `ClaudeE2ETest` passes
- **`--mcp-config` format gotcha** — `mcpServers` wrapper IS required (same schema as `settings.json`); omitting it gives `"Invalid MCP configuration: mcpServers: Does not adhere to MCP server configuration schema"`
- **`ServerStartup.bootstrapRegistry()`** made package-private to enable direct testing

## State Right Now

System fully working end-to-end. 81 tests green. Real Claude can create, list, send input, and get output from sessions via MCP. `mvn test` is the CI gate; `mvn test -Pe2e` validates the Claude integration layer.

## Immediate Next Step

**Authentication design** — nothing exists yet. Start with `/brainstorm`: who authenticates, what threat model (owner-only Mac Mini vs multi-user), and what mechanism (API key header, basic auth, mutual TLS). Needed before any remote/Mac Mini deployment.

## Open Questions / Blockers

- Auth design: API key per-request? Bearer token? Session cookie? Scope unclear.
- `claude` CLI subprocess uses keychain OAuth — `ANTHROPIC_API_KEY` not needed locally. CI would need the env var. `ClaudeE2ETest` handles both cases already.

## References

| Context | Where | Retrieve with |
|---------|-------|---------------|
| E2E testing design | `docs/superpowers/specs/2026-04-05-e2e-testing-design.md` | `cat` |
| Full architecture design | `docs/superpowers/specs/2026-04-03-remotecc-design.md` | `cat` |
| Technical gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only |
| Previous session context | `docs/SESSION-HANDOFF-2026-04-04.md` | `cat` |
| Garden submissions (pending merge) | `~/claude/knowledge-garden/submissions/` | `ls` then `cat` |
