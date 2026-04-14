# 0003 — Authentication Mechanism

Date: 2026-04-14
Status: Accepted

## Context and Problem Statement

Claudony exposes a web dashboard and REST API that must be secured. Two
distinct authentication paths exist: browser users accessing the dashboard,
and the Agent calling the Server's REST API from a co-located or remote process.
A single auth mechanism that fits both use cases cleanly was needed.

## Decision Drivers

* Browser dashboard must be secure without requiring passwords (phishing risk,
  credential management burden)
* Agent→Server calls are machine-to-machine — no human interaction
* Must work on Safari/iOS for iPad access (limits some WebAuthn options)
* GraalVM native compatible — no reflection-heavy auth frameworks
* Personal deployment: one or a small number of registered users, not a
  multi-tenant user database

## Considered Options

* **Option A** — Username/password with session cookies
* **Option B** — WebAuthn passkeys (browser) + API key header (Agent→Server)
* **Option C** — OAuth 2.0 / OIDC (e.g. sign in with Google)

## Decision Outcome

Chosen option: **Option B** (WebAuthn + API key), because passkeys eliminate
password management for the browser path and are natively supported on Apple
devices, while API key is the simplest correct solution for machine-to-machine.

WebAuthn is handled by the `quarkus-security-webauthn` extension. The API key
is auto-generated on first server run, saved to `~/.claudony/api-key`, and sent
by the Agent via `X-Api-Key` header.

### Positive Consequences

* No passwords to manage, store, or leak — passkeys are phishing-resistant
* iCloud Keychain syncs passkeys across Apple devices automatically
* API key path is simple, auditable, and requires no browser interaction
* Rate limiting on auth endpoints prevents brute-force attempts

### Negative Consequences / Tradeoffs

* WebAuthn requires HTTPS in production (or `localhost` exemption in dev)
* First-time registration requires an invite token or direct server access
* WebAuthn origin must match the exact URL used to access the dashboard
  (complicates multi-origin access, e.g. LAN IP vs. hostname)
* Apple iCloud Keychain passkeys use non-zero AAGUID with `NoneAttestation` —
  required a runtime patch to Vert.x (`LenientNoneAttestation` + `WebAuthnPatcher`)

## Pros and Cons of the Options

### Option A — Username/password

* ✅ Universal browser support, simple to implement
* ❌ Passwords must be stored (hashed), managed, and can be phished
* ❌ No sync across devices without a password manager

### Option B — WebAuthn passkeys + API key (chosen)

* ✅ Phishing-resistant, no passwords
* ✅ iCloud Keychain syncs passkeys to iPhone/iPad automatically
* ✅ API key is correct and simple for machine-to-machine
* ❌ WebAuthn origin binding requires care for multi-device access
* ❌ Required patching Vert.x for Apple iCloud Keychain compatibility

### Option C — OAuth 2.0 / OIDC

* ✅ Delegates credential management to an IdP
* ❌ Requires an external IdP (Google, GitHub) — adds external dependency
* ❌ Overkill for a personal single-user deployment
* ❌ More complex Quarkus OIDC configuration

## Links

* `src/main/java/dev/claudony/server/auth/` — WebAuthn + API key implementation
* `src/main/java/dev/claudony/server/auth/ApiKeyService.java` — key generation/persistence
* `docs/BUGS-AND-ODDITIES.md` — Apple iCloud Keychain NoneAttestation patch details
