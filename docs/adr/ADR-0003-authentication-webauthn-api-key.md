# ADR-0003: Authentication via WebAuthn Passkeys and API Key

**Status:** Accepted  
**Date:** 2026-04-05  
**Deciders:** Mark Proctor

---

## Context

RemoteCC exposes a web dashboard and REST API. Once deployed on a networked machine (accessible from a browser or iPad over LAN), it must restrict access to authorised users. Two distinct client types need authentication:

1. **Browser / PWA** — a human using the dashboard or terminal view from an iPad or MacBook browser
2. **Agent** — a programmatic client (the Quarkus Agent process) making REST calls to the Server

Constraints:
- **Passwordless** — the primary client is an iPad; typing passwords repeatedly is poor UX
- **No external service dependency** — no OAuth provider, no identity server, no internet requirement
- **GraalVM native image compatible** — no reflection-heavy auth libraries
- **Device-native** — leverage Touch ID / Face ID / iCloud Keychain already on the device

---

## Alternatives Considered

### Browser authentication

**Basic auth (username + password)**  
Rejected. Passwords on a soft keyboard are friction-heavy. No biometric integration. Passwords stored in browser = weaker security than passkeys.

**OAuth 2.0 / OIDC (e.g. Google, GitHub)**  
Rejected. Requires internet access and an external identity provider. A personal tool running on a home network should not depend on Google being available.

**JWT with username/password login**  
Rejected. Same problem as basic auth (password UX), plus added complexity of token issuance and refresh. No hardware-backed security.

**WebAuthn passkeys (chosen)**  
A passkey is a public-key credential backed by device hardware (Secure Enclave on Apple devices). Authentication uses Touch ID / Face ID. Keys sync to iCloud Keychain — register once on MacBook, authenticate from iPad. No password stored anywhere.

`quarkus-security-webauthn` provides the protocol implementation. Credentials stored locally in `~/.claudony/credentials.json` — no external service.

### Agent authentication

**Shared secret in config**  
Each deployment would require manually setting a secret in config on both Server and Agent. Friction for first run.

**Client certificates (mTLS)**  
Strong security but high operational complexity — certificate generation, rotation, storage. Overkill for a local network tool.

**Auto-provisioned API key (chosen)**  
`ApiKeyService` generates a random key on first Server run, persists it to `~/.claudony/api-key`, and logs a first-run banner. The Agent reads the same file at startup. Zero manual configuration for local deployments; the config property `claudony.agent.api-key` overrides for production scenarios.

---

## Decision

**Browser clients** authenticate via WebAuthn passkeys using `quarkus-security-webauthn`. Registration is invite-only (first user bootstraps; subsequent users require a 24-hour one-time token). Session cookies are signed with a stable encryption key (must be set via `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` in production).

**Agent clients** authenticate via `X-Api-Key` header. The key is auto-provisioned by `ApiKeyService` and shared between Server and Agent via `~/.claudony/api-key`.

Both mechanisms are registered as Quarkus `HttpAuthenticationMechanism` implementations and tried in turn on each request. Valid credentials from either mechanism yield role `user`.

**Apple passkey compatibility:** iCloud Keychain passkeys send `fmt=none` attestation with a non-zero AAGUID, which Vert.x's default `NoneAttestation` rejects. `WebAuthnPatcher` replaces the handler at startup via reflection to accept non-zero AAGUID while still enforcing that `attStmt` is empty.

**Rate limiting:** `AuthRateLimiter` enforces a sliding 5-minute window (10 attempts per IP) on WebAuthn ceremony endpoints to prevent brute-force registration/login.

---

## Consequences

**Positive:**
- Passwordless — Touch ID / Face ID on iPad and MacBook; no soft keyboard friction
- No external dependency — works on an air-gapped home network
- Device-backed security — private key never leaves the Secure Enclave
- Zero-config Agent auth for local deployments

**Negative:**
- WebAuthn requires HTTPS in production — Caddy reverse proxy handles TLS; passkey registration from HTTP localhost only works in dev mode
- Apple passkey AAGUID quirk required a reflection-based patch to Vert.x internals (`WebAuthnPatcher`) — fragile against Vert.x version upgrades
- Session cookies are session-scoped (expire on browser close) — `Max-Age` requires intercepting Quarkus internal cookie issuance (not yet implemented)
- First-run bootstrap (registering the first passkey) requires access to `/auth/register` without an invite token — this is intentional but means the first user must complete registration before the system is locked down

**See also:** `docs/BUGS-AND-ODDITIES.md` for the Apple passkey AAGUID issue and the WebAuthnPatcher implementation detail
