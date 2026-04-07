# RemoteCC — Design Snapshot
**Date:** 2026-04-07
**Topic:** Post native-build verification and WebAuthn config fixes
**Supersedes:** [2026-04-06-post-auth-hardening](2026-04-06-post-auth-hardening.md)
**Superseded by:** [2026-04-07-post-api-key-provisioning](2026-04-07-post-api-key-provisioning.md)

---

## Where We Are

RemoteCC has a verified, working native binary. The GraalVM build passes cleanly with `quarkus-security-webauthn` included — 0 reflection errors, 61 MB binary, 0.087s startup. Two silent WebAuthn configuration bugs have been fixed: the relying-party and origin config keys were using wrong names and silently ignored since the project started, and the session cookie encryption key was unset, causing sessions to be invalidated on every server restart. Both are now corrected. 116 tests pass. The system is ready for Mac Mini deployment pending API key provisioning strategy.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| WebAuthn RP config keys | `relying-party.id`, `relying-party.name`, `origin` (singular) | Correct SmallRye Config field names from `WebAuthnRunTimeConfig` bytecode | `rp.id`, `rp.name`, `origins` — standard spec abbreviations but wrong for Quarkus; silently ignored |
| Session encryption key | `quarkus.http.auth.session.encryption-key` | Actual `@ConfigItem` annotation name on `HttpConfiguration.encryptionKey` | `quarkus.http.encryption-key` — field name guess, unrecognised and silently ignored |
| Dev encryption key | Fixed value in `%dev` profile | Sessions survive restarts in dev — no re-authentication on every code change | Random key per startup (previous behaviour) |
| Encryption key discovery method | `jar xf` + `javap -verbose` to read constant pool | Only way to find `@ConfigItem(name=...)` override values; docs and field name both mislead | Guessing from field name or docs (both wrong) |

## Where We're Going

**Next steps:**
- Agent API key provisioning on Mac Mini — the last deployment blocker; options: env var in launchd plist, config file, or first-run wizard
- Write pre-1.0 ADRs: terminal streaming (pipe-pane + FIFO), MCP transport (HTTP JSON-RPC), auth mechanism (WebAuthn + API key)

**Open questions:**
- Agent API key provisioning: launchd plist env var is simplest but opaque; config file is discoverable but needs file-permission hardening; first-run wizard is best UX but most work — which fits the personal-tool use case?
- Session expiry: WebAuthn cookies are still session cookies (expire on browser close); `Max-Age` requires intercepting Quarkus's internal cookie issuance — is the current behaviour acceptable?

## Linked ADRs

*(No ADRs yet — pre-1.0 ADRs flagged: terminal streaming, MCP transport, auth mechanism.)*

## Context Links

- Living design doc: [`docs/DESIGN.md`](../DESIGN.md)
- Previous snapshot: [`docs/design-snapshots/2026-04-06-post-auth-hardening.md`](2026-04-06-post-auth-hardening.md)
