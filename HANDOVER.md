# Handover — 2026-04-06

**Head commit:** `8037c27` — docs: add design snapshot 2026-04-06-post-blog-catchup
**Previous handover:** `git show HEAD~1:HANDOVER.md` | diff: `git diff HEAD~1 HEAD -- HANDOVER.md`

## What Changed This Session

- **Blog entry 2 written** — `docs/blog/2026-04-05-02-locking-the-door.md` (auth implementation — WebAuthn passkeys, invite tokens, API key, auth review catches)
- **Blog catch-up complete** — "Entry 3" (TUI garble fix) decided → knowledge garden instead of blog
- **Design snapshot** — `docs/design-snapshots/2026-04-06-post-blog-catchup.md` supersedes `2026-04-06-full-system-state.md`
- **Knowledge garden** — 2 new entries: TUI pane dimensions not synced to browser viewport (`tools/tmux.md`), Quarkus `Authenticator.publicKeyAlgorithm` has no getter/setter (`quarkus/webauthn.md`)
- **CLAUDE.md** — added `## Project Type` section header (was bare `**Type:** java` at top; tooling hook required the section)

## Immediate Next Step

**Production-harden auth** — rate limiting on auth endpoints, session expiry, remove dev quick-login before Mac Mini deployment. Start with `docs/superpowers/specs/2026-04-05-auth-design.md` for scope, then `src/main/java/dev/remotecc/server/auth/`.

## Open Questions / Blockers

- Native binary not verified since `quarkus-security-webauthn` was added — run native build to check
- ADRs flagged but not created: terminal streaming, MCP transport, auth mechanism
- Agent API key provisioning on fresh Mac Mini — env var, config file, or first-run wizard?

## References

| Context | Where | Retrieve with |
|---------|-------|---------------|
| Design state | `docs/design-snapshots/2026-04-06-post-blog-catchup.md` | `cat` |
| Blog entries | `docs/blog/` | `ls` then `cat` |
| Technical gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only; detail on demand |
| Auth design | `docs/superpowers/specs/2026-04-05-auth-design.md` | `cat` |
| Previous handover | git history | `git show HEAD~1:HANDOVER.md` |
