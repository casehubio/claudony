# Handover — 2026-04-06

**Head commit:** `642d611` — docs: add design snapshot 2026-04-06-full-system-state
**Previous handover:** `git show HEAD~1:HANDOVER.md` | diff: `git diff HEAD~1 HEAD -- HANDOVER.md`

## What Changed This Session

- **Auth system live** — WebAuthn passkeys + API key. 22 commits from a separate Claude session. Reviewed and committed unchanged.
- **Dev quick login** — overlay dialog with one-click cookie-based auth (dev mode only, `POST /auth/dev-login`)
- **Silent 401 fixed** — `dashboard.js` now redirects to auth dialog on 401 instead of silently doing nothing
- **Directory split** — `~/.remotecc/` config/credentials, `~/remotecc-workspace/` session default (was mixed in `~/.remotecc/`)
- **Duplicate session detection** — 409 on create with existing name, `?overwrite=true` to replace, live validation in UI
- **TUI auto-resize** — WebSocket URL now carries `/{cols}/{rows}`; server calls `tmux resize-pane` before pipe-pane; TUI redraws before user sees garble
- **Blog moved** — `docs/project-blog/` → `docs/blog/`, CLAUDE.md updated with style guide pointer
- **First design snapshot** — `docs/design-snapshots/2026-04-06-full-system-state.md`
- **Blog entry 1 written** — `docs/blog/2026-04-05-01-testing-what-wasnt-tested.md`
- 106 tests passing

## Immediate Next Step

**Write blog entries 2 and 3** — draft for Entry 2 ("Locking the Door") was approved in Typora but not yet committed. Open `/tmp/remotecc-blog-entry2-preview.md`, write to `docs/blog/2026-04-05-02-locking-the-door.md`, commit, then draft Entry 3 ("First Real Use" — auth 401 silent failure, wrong default dir, TUI garble fix via SIGWINCH research).

## Open Questions / Blockers

- Auth not production-hardened: no rate limiting, no session expiry, dev quick-login must be removed before Mac Mini deployment
- Native binary not verified since `quarkus-security-webauthn` was added — may need GraalVM reflection config
- ADRs flagged but not created: terminal streaming, MCP transport, auth mechanism

## References

| Context | Where | Retrieve with |
|---------|-------|---------------|
| Design state | `docs/design-snapshots/2026-04-06-full-system-state.md` | `cat` |
| Blog entries | `docs/blog/` | `ls` then `cat` |
| Technical gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only |
| Previous handover | git history | `git show HEAD~1:HANDOVER.md` |
| Auth design | `docs/superpowers/specs/2026-04-05-auth-design.md` | `cat` |
