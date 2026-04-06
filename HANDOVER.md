# Handover — 2026-04-07

**Head commit:** `fe6fa3f` — docs: session wrap 2026-04-07
**Previous handover:** `git show HEAD~1:HANDOVER.md` | diff: `git diff ae65a6e HEAD -- HANDOVER.md`

## What Changed This Session

- **Native binary verified** — GraalVM build passes cleanly with `quarkus-security-webauthn`; 61 MB, 0.087s startup, zero reflection errors
- **WebAuthn config keys fixed** — `rp.id/name/origins` were silently ignored since day one; correct: `relying-party.id`, `relying-party.name`, `origin` (singular)
- **Session encryption key fixed** — `quarkus.http.auth.session.encryption-key` (found via `javap` bytecode inspection of `WebAuthnRecorder$1`); dev profile now has fixed key; sessions survive restarts
- **DESIGN.md updated** — Key Constraints section corrected (sessions survive restarts now)
- **CLAUDE.md updated** — encryption key env var documented; session expiry note corrected
- **Garden** — GE-0045 (WebAuthn config keys), GE-0046 (encryption key property), GE-0047 (javap technique) submitted
- **116 tests still passing** throughout

## Immediate Next Step

**Agent API key provisioning on Mac Mini** — the last deployment blocker. How does `remotecc.agent.api-key` get set on a headless machine? Options: env var in launchd plist (simple, opaque), config file with `rw-------` permissions (discoverable), or first-run wizard (best UX, most work). Pick one and implement it.

## Open Questions / Blockers

- Agent API key provisioning strategy (see above — next session's focus)
- Session expiry: cookies still expire on browser close; `Max-Age` requires intercepting Quarkus's internal cookie issuance — acceptable for personal-tool use case?
- Pre-1.0 ADRs unwritten: terminal streaming, MCP transport, auth mechanism

## References

| Context | Where | Retrieve with |
|---------|-------|---------------|
| Design state | `docs/design-snapshots/2026-04-07-post-native-build-webauthn-fixes.md` | `cat` |
| Living design doc | `docs/DESIGN.md` | `cat` |
| Blog | `docs/blog/2026-04-07-01-two-silent-bugs-and-a-working-binary.md` | `cat` |
| Technical gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only; detail on demand |
| Previous handover | git history | `git show ae65a6e:HANDOVER.md` |
