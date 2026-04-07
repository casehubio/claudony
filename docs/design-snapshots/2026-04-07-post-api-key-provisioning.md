# RemoteCC — Design Snapshot
**Date:** 2026-04-07
**Topic:** Post API key provisioning — deployment-ready
**Supersedes:** [2026-04-07-post-native-build-webauthn-fixes](2026-04-07-post-native-build-webauthn-fixes.md)
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

RemoteCC is deployment-ready on Mac Mini. The last deployment blocker — API key
provisioning — is resolved. The server now auto-generates a key on first run,
persists it to `~/.remotecc/api-key` with `rw-------` permissions, and logs a
prominent banner with copy-paste instructions. The agent auto-discovers the key
on the same machine; on a different machine, it warns clearly and starts degraded.
124 tests pass. The native binary from last session remains valid — no new
endpoints were added.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| API key provisioning strategy | First-run wizard: auto-generate on server, persist to `~/.remotecc/api-key` | Zero config for same-machine setup; self-documenting file; survives restarts | Env var in launchd plist (opaque, extra setup step); interactive stdin prompt (most work) |
| Key resolution order | Config property → file → generate/warn | Explicit always wins; file enables same-machine auto-discovery; generation is last resort | Single-source (config only) — requires manual setup every time |
| Key storage | `~/.remotecc/api-key`, plain text, 600 permissions | Co-located with credentials, discoverable, secure | Keychain (overkill for personal tool), env var file (non-standard location) |
| `ApiKeyService` architecture | New `@ApplicationScoped` bean; `ApiKeyAuthMechanism` and `ApiKeyClientFilter` inject it | Single source of truth for runtime key; decoupled from Quarkus config resolution | Inline in startup beans with shared mutable field (messy) |
| Agent degraded mode | Warn prominently and start anyway | Consistent with "server not reachable" handling; 401 errors are self-explanatory | Hard fail on startup (breaks workflow when server isn't ready yet) |

## Where We're Going

The system is now ready for first real deployment to Mac Mini. The next phase
is operational: deploying, connecting the agent from MacBook, and using the
system in anger.

**Next steps:**
- Deploy to Mac Mini: copy binary, configure launchd plist, set `remotecc.bind=0.0.0.0`
- Run server for first time — verify banner fires and `~/.remotecc/api-key` is written
- Configure agent on MacBook with key from Mac Mini, verify MCP tools work end-to-end
- Document launchd plist setup in `docs/DEPLOYMENT.md` (doesn't exist yet)

**Open questions:**
- Session expiry: cookies still expire on browser close; intercepting Quarkus's internal cookie issuance to set `Max-Age` is non-trivial — acceptable for personal tool?
- Docker sandbox per session (idea logged): worthwhile before wider use?
- Pre-1.0 ADRs unwritten: terminal streaming choice, MCP transport choice, auth mechanism choice — worth capturing before the design drifts further?

## Linked ADRs

*(No ADRs exist yet for this project — significant decisions are captured in design snapshots only.)*

## Context Links

- Spec: `docs/superpowers/specs/2026-04-07-api-key-provisioning-design.md`
- Implementation plan: `docs/superpowers/plans/2026-04-07-api-key-provisioning.md`
