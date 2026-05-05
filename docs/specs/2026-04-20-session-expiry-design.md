# Session Expiry Enforcement Design

**Date:** 2026-04-20
**Status:** Approved

---

## Problem

`claudony.session-timeout` (default 7 days) is already wired to `quarkus.webauthn.session-timeout` — auth session cookies expire correctly. But tmux sessions in the `SessionRegistry` are never cleaned up. Sessions accumulate indefinitely regardless of inactivity.

---

## Design

### Interface and Registry

```java
public interface ExpiryPolicy {
    String name();
    boolean isExpired(Session session, Duration timeout);
}
```

`ExpiryPolicyRegistry` is `@ApplicationScoped`. Injected with `@Any Instance<ExpiryPolicy>` at startup, it builds `Map<String, ExpiryPolicy>` from each bean's `name()`. Logs a WARNING at startup if `claudony.session-expiry-policy` doesn't match any registered bean. `resolve(String name)` returns the matching policy or the configured default. New policies are just new `@ApplicationScoped` beans — no registration code needed.

---

### Three Policy Implementations

**`UserInteractionExpiryPolicy` (name: `"user-interaction"`)**

Checks `session.lastActive()` against `now - timeout`. Pure in-memory, no tmux calls. Requires activity tracking (Section 4) to keep `lastActive` current.

**`TerminalOutputExpiryPolicy` (name: `"terminal-output"`)**

Calls `tmux display-message -p "#{pane_activity}" -t <name>` — tmux tracks pane activity natively as a Unix timestamp. Parses the result, compares against `now - timeout`. If the tmux call fails (session gone), returns `true` (expired). No state stored in the `Session` model.

**`StatusAwareExpiryPolicy` (name: `"status-aware"`)**

Calls `tmux display-message -p "#{pane_current_command}" -t <name>`. If the foreground process is **not** a known shell (`bash`, `zsh`, `sh`, `dash`, `fish`), something is actively running — never expire. If it is a shell, falls back to the user-interaction check against `session.lastActive()`. Does not depend on `SessionStatus` being tracked — reads real process state from tmux directly.

---

### Session Model and Request Changes

`Session` record gains: `Optional<String> expiryPolicy`. `null`/empty = use global default. Carried through unchanged by `withStatus()` and `withLastActive()`.

`CreateSessionRequest` gains: optional `String expiryPolicy`. Any authenticated caller may set it at session creation — no additional role check needed (the endpoint is already `@Authenticated`). Unknown policy names fall back to the global default with a log warning; no 400 at the JSON layer.

`SessionResponse` exposes the effective policy name — `session.expiryPolicy().orElse(config.sessionExpiryPolicy())` resolved in `SessionResource` where `ClaudonyConfig` is already injected — so the dashboard can show which policy a session is running under.

`SessionRegistry` is unchanged.

---

### Activity Tracking

`SessionRegistry` gains a `touch(String id)` helper — `computeIfPresent` with `withLastActive()` — keeping call sites to one-liners.

`withLastActive()` is called in:
- `TerminalWebSocket.onOpen()` — user opened a terminal view
- `TerminalWebSocket.onMessage()` — user sent keyboard input
- `SessionResource.sendInput()` — REST input path (MCP agent)

Not tracked: `resize`, `getOutput`, `list`, `get` — passive or ambient, not meaningful activity signals.

`terminal-output` and `status-aware` policies query tmux directly and degrade gracefully for bootstrapped sessions where `lastActive` starts at `createdAt`.

---

### Scheduler

`SessionIdleScheduler` is `@ApplicationScoped`, enabled only when `config.isServerMode()`.

```
@Scheduled(every = "5m", delayed = "1m")
```

Per tick, for each session in `registry.all()`:
1. Resolve policy: session's `expiryPolicy` ?? `config.sessionExpiryPolicy()`
2. `policy.isExpired(session, config.sessionTimeout())`
3. If expired: fire `SessionExpiredEvent` via CDI `Event<SessionExpiredEvent>`, then `tmux.killSession(session.name())` (best-effort, swallow `IOException`), then `registry.remove(session.id())`
4. Log INFO: `"Expired session '%s' (policy=%s, lastActive=%s)"`

The event fires **before** the kill so observers can interact with the session (e.g. send a WebSocket close message) while it still exists.

---

### Expiry Event

```java
public record SessionExpiredEvent(Session session) {}
```

Initial observer: `TerminalWebSocket` fires a structured close message (`{"type":"session-expired"}`) to any connected WebSocket clients for the session before they get a raw disconnect.

Future observer: CaseHub worker lifecycle integration (Phase B).

---

### Config

`ClaudonyConfig` gains:

```java
@WithName("session-expiry-policy")
@WithDefault("user-interaction")
String sessionExpiryPolicy();
```

`application.properties`:
```properties
# Controls both WebAuthn auth session lifetime and tmux session idle expiry.
# Default: P7D. Override via CLAUDONY_SESSION_TIMEOUT env var.
claudony.session-expiry-policy=user-interaction
```

`claudony.session-timeout` continues to drive both WebAuthn auth session expiry and tmux session idle expiry. If they need to diverge in future, a `claudony.idle-timeout` can be split out then.

---

### Tests

**Unit (no `@QuarkusTest`):**
- `ExpiryPolicyRegistryTest` — resolves known names, falls back to default for unknown, logs warning for missing configured policy
- `UserInteractionExpiryPolicyTest` — within timeout → not expired; beyond → expired
- `StatusAwareExpiryPolicyTest` — mocks tmux `display-message`; non-shell command → never expired; shell command → delegates to `lastActive`

**Integration (`@QuarkusTest`):**
- `SessionIdleSchedulerTest` — calls `expiryCheck()` (package-private test hook); seeds registry with expired and non-expired sessions; verifies kill called for expired only and CDI event fired
- `TerminalOutputExpiryPolicyTest` — real tmux session (same pattern as `TmuxServiceTest`); `@DisabledIfEnvironmentVariable` if tmux unavailable

**Not tested separately:** CDI event observer wiring on the WebSocket side — confirmed by the scheduler integration test firing the event; live WebSocket connection coupling makes a dedicated unit test impractical.

**Expected new tests:** ~10–12.

---

## Files Touched

| File | Change |
|---|---|
| `server/model/Session.java` | Add `Optional<String> expiryPolicy` field |
| `server/model/SessionResponse.java` | Expose resolved policy name |
| `server/model/SessionExpiredEvent.java` | New record |
| `server/model/CreateSessionRequest.java` | Add optional `expiryPolicy` field |
| `server/SessionRegistry.java` | Add `touch(String id)` |
| `server/TerminalWebSocket.java` | Call `registry.touch()` on open/message; add expiry event observer |
| `server/SessionResource.java` | Call `registry.touch()` on input |
| `server/expiry/ExpiryPolicy.java` | New interface |
| `server/expiry/ExpiryPolicyRegistry.java` | New registry bean |
| `server/expiry/UserInteractionExpiryPolicy.java` | New policy |
| `server/expiry/TerminalOutputExpiryPolicy.java` | New policy |
| `server/expiry/StatusAwareExpiryPolicy.java` | New policy |
| `server/expiry/SessionIdleScheduler.java` | New scheduler |
| `config/ClaudonyConfig.java` | Add `sessionExpiryPolicy()` |
| `resources/application.properties` | Add `claudony.session-expiry-policy` |
