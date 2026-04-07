# Handover — 2026-04-07

**Head commit:** `1c5dfd2` — docs: add project blog entry 2026-04-07-02-zero-configuration
**Previous handover:** `git show HEAD~1:HANDOVER.md` | diff: `git diff ddfb40f HEAD -- HANDOVER.md`

## What Changed This Session

- **API key provisioning complete** — `ApiKeyService` auto-generates key on first server run, persists to `~/.remotecc/api-key` (600 perms), logs banner. Agent auto-discovers same-machine file; different machine gets warning with env var to set.
- **`ApiKeyService` wired in** — `ApiKeyAuthMechanism`, `ApiKeyClientFilter`, `ServerStartup`, `AgentStartup` all updated
- **Quarkus timing gotcha** — `HttpAuthenticationMechanism` injections resolve before `@Observes StartupEvent` fires; fixed with `@PostConstruct autoInit()` on `ApiKeyService` (GE-0053 submitted)
- **124 tests passing** (was 116 — 8 new in `ApiKeyServiceTest`)
- **CLAUDE.md updated** — `ApiKeyService` in structure, `api-key` comment corrected
- **Design snapshot** — `docs/design-snapshots/2026-04-07-post-api-key-provisioning.md`
- **Blog** — `docs/blog/2026-04-07-02-zero-configuration.md`

## Immediate Next Step

**Deploy to Mac Mini** — the system is now deployment-ready. Steps:
1. Copy native binary to Mac Mini
2. Configure launchd plist (`remotecc.bind=0.0.0.0`, no `remotecc.agent.api-key` needed)
3. Start server — verify first-run banner fires and `~/.remotecc/api-key` is written
4. Configure agent on MacBook with key from Mac Mini (`export REMOTECC_AGENT_API_KEY=...`)
5. Verify MCP tools work end-to-end
6. Write `docs/DEPLOYMENT.md` (doesn't exist yet)

## Open Questions / Blockers

- Session expiry: cookies still expire on browser close; `Max-Age` requires intercepting Quarkus internal cookie issuance — acceptable for personal tool?
- Pre-1.0 ADRs still unwritten: terminal streaming, MCP transport, auth mechanism choices
- Docker sandbox per session (idea logged): worthwhile before wider use?

## References

| Context | Where | Retrieve with |
|---------|-------|---------------|
| Design state | `docs/design-snapshots/2026-04-07-post-api-key-provisioning.md` | `cat` |
| Living design doc | `docs/DESIGN.md` | `cat` |
| Blog | `docs/blog/2026-04-07-02-zero-configuration.md` | `cat` |
| Technical gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only; detail on demand |
| Previous handover | git history | `git show ddfb40f:HANDOVER.md` |
