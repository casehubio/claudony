# RemoteCC — Design Snapshot
**Date:** 2026-04-06
**Topic:** Post auth-hardening and test quality pass
**Supersedes:** [2026-04-06-post-blog-catchup](2026-04-06-post-blog-catchup.md)
**Superseded by:** *(leave blank — filled in if this snapshot is later superseded)*

---

## Where We Are

RemoteCC has production-ready authentication. WebAuthn passkeys + API key auth are implemented, hardened, and covered by 116 passing tests. The `/app/*` dashboard is now protected (it was previously open without login). A rate limiter guards the WebAuthn ceremony and invite-token endpoints. The dev-login backdoor is closed in non-development modes. `docs/DESIGN.md` exists as a living architectural overview. The system is ready for native binary verification and Mac Mini deployment.

## How We Got Here

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Rate limiter placement | Vert.x `@Observes Router` handler | Covers WebAuthn ceremony paths (`/q/webauthn/*`) which bypass JAX-RS | JAX-RS `ContainerRequestFilter` (misses extension-managed paths) |
| Rate limiter state | Sliding window (`ArrayDeque<Instant>` per IP, synchronized) | Simple, in-memory, no dependency | Fixed window (allows burst at boundary), token bucket (more complex) |
| Clock injection | `Supplier<Instant>` field + `setClockForTest()` | Zero production overhead; avoids Mockito `mockStatic` or `Thread.sleep` | `java.time.Clock` injection (heavier), `mockStatic` (error-prone) |
| `@QuarkusTest` isolation | `resetForTest()` + `@AfterEach` in test classes with stateful beans | All `@QuarkusTest` classes share one app instance; without cleanup, state bleeds between classes | No cleanup (causes misleading test failures in unrelated classes) |
| `/app/*` auth protection | Added to `quarkus.http.auth.permission.protected.paths` | Was missing — dashboard was open without login | Redirect-only in JS (bypassable) |
| Dev cookie scope | `LaunchMode.DEVELOPMENT` guard in `ApiKeyAuthMechanism` | Cookie was accepted in all modes including production | Remove endpoint entirely (useful for local dev) |

## Where We're Going

**Next steps:**
- Verify native binary compiles cleanly with `quarkus-security-webauthn` — this is the deployment blocker; hasn't been built since auth was added
- Mac Mini deployment: bind to `0.0.0.0`, set real WebAuthn RP ID/origins, configure as launchd service
- Session expiry: WebAuthn cookies are still session cookies; implementing `Max-Age` requires intercepting Quarkus's internal cookie issuance

**Open questions:**
- Does the native binary still compile? GraalVM reflection requirements for `quarkus-security-webauthn` are unknown until tested
- Session expiry — is "expires on browser close or server restart" acceptable for the personal-tool use case, or is a hard `Max-Age` needed?
- Agent API key provisioning on fresh Mac Mini: env var in launchd plist, config file, or first-run wizard?

## Linked ADRs

*(No ADRs created yet — key decisions are captured in the snapshot decision tables above. ADRs to create before 1.0: terminal streaming, MCP transport, auth mechanism.)*

## Context Links

- Living design doc: [`docs/DESIGN.md`](../DESIGN.md)
- Auth design spec: [`docs/superpowers/specs/2026-04-05-auth-design.md`](superpowers/specs/2026-04-05-auth-design.md)
- Previous snapshot: [`docs/design-snapshots/2026-04-06-post-blog-catchup.md`](2026-04-06-post-blog-catchup.md)
