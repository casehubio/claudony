# Design: Configurable Session Timeout

**Date:** 2026-04-14  
**Status:** Approved  
**Feature:** Persistent WebAuthn session cookies with configurable inactivity timeout (default 7 days)

---

## Problem

Quarkus WebAuthn defaults to a 30-minute inactivity timeout (`quarkus.webauthn.session-timeout=PT30M`).
After 30 minutes of inactivity — including simply closing the browser — the encrypted session
cookie expires and the user must authenticate again via passkey.

For a personal deployment accessed from iPhone, iPad, and MacBook, this is impractical: every
browser open requires passkey authentication. A 7-day inactivity window matches real-world usage.

---

## Solution

`WebAuthnRunTimeConfig` (Quarkus 3.9.5) exposes `sessionTimeout()` and `newCookieInterval()`
as configurable `Duration` properties. No Vert.x filter, no cookie rewriting, no Quarkus
patching required — this is a pure config change.

---

## Architecture

### New config property: `claudony.session-timeout`

Add to `ClaudonyConfig`:

```java
@WithName("session-timeout")
@WithDefault("P7D")   // 7 days
Duration sessionTimeout();
```

The `P7D` notation is ISO-8601 for 7 days. SmallRye Config also accepts the lowercase
simplified form `7d` — both are equivalent.

### `application.properties` additions

```properties
# Session inactivity timeout — how long a login stays valid without activity.
# Default: 7 days (P7D). Override via claudony.session-timeout env var or config.
quarkus.webauthn.session-timeout=${claudony.session-timeout}

# Cookie renewal interval — how often an active session's cookie is refreshed.
# Default: 1H. With a 7-day inactivity window, refreshing every minute (Quarkus default)
# is unnecessary overhead; 1H balances responsiveness with server load.
quarkus.webauthn.new-cookie-interval=1H
```

### How it works

`PersistentLoginManager` (Quarkus internals) creates the Set-Cookie header with
`Max-Age = sessionTimeout.toSeconds()`. With 7 days, that is `Max-Age=604800`.

After each `newCookieInterval` (1 hour) of activity, a new cookie with a refreshed
`Max-Age` is issued, effectively extending the session for another 7 days from the
last active hour. After 7 days of no requests, the cookie's `Max-Age` has elapsed
and the user must log in again.

---

## Timeout Semantics

This is an **inactivity timeout**, not an absolute expiry:

| Scenario | Result |
|---|---|
| User active daily | Session renews every hour; effectively indefinite |
| User away < 7 days | Returns, still logged in |
| User away > 7 days | Cookie expired, passkey re-authentication required |
| User closes browser, reopens within 7 days | Still logged in (cookie is persistent, not session-scoped) |
| User explicitly logs out | Cookie cleared immediately |

---

## Configuration Reference

| Property | Default | Description |
|---|---|---|
| `claudony.session-timeout` | `P7D` (7 days) | Inactivity timeout; controls `quarkus.webauthn.session-timeout` |
| `quarkus.webauthn.new-cookie-interval` | `1H` (hardcoded) | How often an active session's cookie is refreshed |

Override example (30-day timeout for a low-activity deployment):
```bash
export CLAUDONY_SESSION_TIMEOUT=720H
```

---

## Testing

### `SessionTimeoutConfigTest` (`@QuarkusTest`, 3 tests)

Verifies the config wiring end-to-end inside the live Quarkus runtime:

| Test | Assertion |
|---|---|
| `sessionTimeoutDefaultsTo7Days` | `claudonyConfig.sessionTimeout()` == `Duration.ofDays(7)` |
| `quarkusWebAuthnReceivesSessionTimeout` | `webAuthnConfig.sessionTimeout()` == `Duration.ofDays(7)` |
| `newCookieIntervalIsOneHour` | `webAuthnConfig.newCookieInterval()` == `Duration.ofHours(1)` |

The second test is the critical one: it verifies that the `${claudony.session-timeout}`
reference in `application.properties` is resolved correctly and reaches Quarkus's
WebAuthn config.

### Manual E2E verification

After deployment:

1. Log in via passkey at `/auth/login`
2. Open browser dev tools → Application → Cookies
3. Find the session cookie (name: `quarkus-credential` by default)
4. Confirm `Max-Age` ≈ 604800 (7 days in seconds)
5. Close browser, reopen, navigate to `/app/` — confirm no login redirect

---

## Files Changed

| File | Change |
|---|---|
| `src/main/java/dev/claudony/config/ClaudonyConfig.java` | Add `sessionTimeout()` — `Duration`, `@WithDefault("P7D")` |
| `src/main/resources/application.properties` | Add `quarkus.webauthn.session-timeout` and `quarkus.webauthn.new-cookie-interval` |
| `src/test/java/dev/claudony/config/SessionTimeoutConfigTest.java` | New — 3 `@QuarkusTest` integration tests |
| `CLAUDE.md` | Update test count 159 → 162 |

---

## What This Does NOT Change

- Dev/test profiles are unaffected — the new property defaults apply in all profiles, but dev sessions were already long-lived (dev-login cookie has no Max-Age restriction)
- The explicit `QUARKUS_WEBAUTHN_SESSION_TIMEOUT` env var overrides `claudony.session-timeout` (higher ordinal)
- `quarkus.webauthn.new-cookie-interval` is not exposed as a `claudony.*` property — it is an implementation detail with no user-facing reason to configure it
- Logout (`/auth/logout` or equivalent) clears the cookie immediately regardless of timeout
