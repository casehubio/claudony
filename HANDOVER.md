# Handover — 2026-04-06

**Head commit:** `6709527` — docs: session wrap 2026-04-06
**Previous handover:** `git show HEAD~1:HANDOVER.md` | diff: `git diff HEAD~1 HEAD -- HANDOVER.md`

## What Changed This Session

- **Auth hardening** — `AuthRateLimiter` (sliding window, 10/5min per IP, Vert.x route handler covering `/q/webauthn/*` + `/auth/register`); `/app/*` added to protected paths (was missing — dashboard was open); dev cookie now DEVELOPMENT-only in `ApiKeyAuthMechanism`
- **`docs/DESIGN.md` created** — living architectural overview; updated by `/update-design` or `java-git-commit`
- **Test quality pass** — HTTP-level 429 test (`AuthRateLimiterHttpTest`); dev cookie rejection test; window expiry test via `Supplier<Instant>` clock injection; `updateOrStoreWebAuthnCredentials` interface tests; `/app/*` unauthenticated protection test
- **`@QuarkusTest` singleton isolation pattern** — `resetForTest()` + `@AfterEach` required when `@ApplicationScoped` beans hold mutable state; all classes share one app instance per run
- **116 tests passing** (was 106)
- **Garden** — GE-0036 (`@ApplicationScoped` state bleeds across `@QuarkusTest` classes), GE-0037 (`Supplier<Instant>` clock injection) submitted
- **Design snapshot** — `docs/design-snapshots/2026-04-06-post-auth-hardening.md` supersedes post-blog-catchup
- **Blog** — `docs/blog/2026-04-06-01-closing-the-gaps.md`

## Immediate Next Step

**Verify native binary** — run `JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home mvn package -Pnative -DskipTests` and check for GraalVM reflection errors from `quarkus-security-webauthn`. This is the deployment blocker — hasn't been built since auth was added.

## Open Questions / Blockers

- Native binary compatibility with `quarkus-security-webauthn` — unknown until built
- Session expiry — WebAuthn cookies are session cookies; `Max-Age` requires intercepting Quarkus's internal cookie issuance (non-trivial)
- Agent API key provisioning on fresh Mac Mini — env var in launchd plist, config file, or first-run wizard?
- ADRs still unwritten: terminal streaming, MCP transport, auth mechanism (flagged as pre-1.0)

## References

| Context | Where | Retrieve with |
|---------|-------|---------------|
| Design state | `docs/design-snapshots/2026-04-06-post-auth-hardening.md` | `cat` |
| Living design doc | `docs/DESIGN.md` | `cat` |
| Blog | `docs/blog/2026-04-06-01-closing-the-gaps.md` | `cat` |
| Technical gotchas | `~/claude/knowledge-garden/GARDEN.md` | index only; detail on demand |
| Previous handover | git history | `git show HEAD~1:HANDOVER.md` |
