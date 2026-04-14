# Design: Per-Deployment Session Encryption Key

**Date:** 2026-04-14  
**Status:** Approved  
**Feature:** `EncryptionKeyConfigSource` — auto-generated, persisted, per-deployment session cookie key

---

## Problem

Quarkus encrypts WebAuthn session cookies with `quarkus.http.auth.session.encryption-key`. Until
this feature, Claudony shipped a hardcoded key in `application.properties`:

```properties
%prod.quarkus.http.auth.session.encryption-key=XrK9mP2vLq8nT5wY3jH7bN4cF6dA1eZ0
```

**This is a shared secret committed to a public git repository.** Every Claudony deployment in
the world uses the same key. Consequences:

- The key is permanently compromised — it appears in git history and will never be truly secret.
- An encrypted session cookie from any Claudony deployment could be decrypted by any other
  deployment, or by anyone who has read the source code.
- Cookie forgery (crafting a valid session cookie without logging in) is theoretically possible
  for anyone who knows the key and can reproduce the encryption scheme.

The fix: each deployment generates its own unique key on first run and persists it to
`~/.claudony/encryption-key`. The key never leaves the machine it was generated on.

---

## Security Properties After This Change

| Property | Before | After |
|---|---|---|
| Key uniqueness | Shared across all deployments | Unique per machine |
| Key secrecy | Public (in git repo) | Private (`rw-------`, owner-only) |
| Key in source code | Yes | No — generated at runtime |
| Key in git history | Yes (permanently) | No |
| Session portability across deployments | Yes (undesirable) | No — keys differ |
| Admin override via env var | Yes | Yes (unchanged) |
| Dev/test sessions stable across restarts | Yes | Yes (dev/test keys remain in application.properties) |

---

## Why a MicroProfile `ConfigSource`

Quarkus reads `quarkus.http.auth.session.encryption-key` during bootstrap, before the CDI
container starts. A `@Startup` bean or `@PostConstruct` method cannot supply this value — CDI
starts after config is resolved.

The only way to inject a runtime-generated value into Quarkus config is a custom
`org.eclipse.microprofile.config.spi.ConfigSource`, which SmallRye Config consults at
bootstrap time, before any framework initialisation.

---

## Architecture

### New class: `EncryptionKeyConfigSource`

**Package:** `dev.claudony.config`  
**Implements:** `org.eclipse.microprofile.config.spi.ConfigSource`  
**Annotation:** `@RegisterForReflection` (native image safety)  
**Registration:** `META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource`

### Config ordinal stack

SmallRye Config resolves values by ordinal (highest wins):

| Source | Ordinal | Used for |
|---|---|---|
| System properties | 400 | — |
| Environment variables | 300 | `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` — admin override |
| `application.properties` (profile-specific) | 250 | `%dev` and `%test` fixed keys |
| **`EncryptionKeyConfigSource`** | **200** | **prod: auto-generated per-deployment key** |

In **dev** and **test** profiles: the profile-specific keys in `application.properties` (ordinal 250)
win. `EncryptionKeyConfigSource` provides a value at ordinal 200, which is ignored.

In **prod** profile: the hardcoded key is removed from `application.properties`. No source at
ordinal 250 provides this key. `EncryptionKeyConfigSource` (ordinal 200) is the highest source
with a value, so it wins.

If `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` is set (ordinal 300), it wins regardless of profile.
This is the correct override path for operators who manage secrets via env vars or secret stores.

### `application.properties` change

Remove:
```properties
%prod.quarkus.http.auth.session.encryption-key=XrK9mP2vLq8nT5wY3jH7bN4cF6dA1eZ0
```

Retain (unchanged):
```properties
%dev.quarkus.http.auth.session.encryption-key=WDvVpqyk2J-CdtTj6FTpEIus7ofJ9Wh0eZUysCwEuZc
%test.quarkus.http.auth.session.encryption-key=test-encryption-key-not-for-prod-1234567
```

---

## Key Lifecycle

### Resolution order within `EncryptionKeyConfigSource.getValue()`

Only called for `"quarkus.http.auth.session.encryption-key"`. Returns `null` for all other keys.

```
1. Is cached key present in memory?
     Yes → return cached value (no file I/O after first call)

2. Does ~/.claudony/encryption-key exist?
     Yes → read it, validate non-blank, cache, return

3. (First run) Does ~/.claudony/ exist?
     No → create it (mkdir -p equivalent)
     If creation fails → WARN, skip to step 5

4. Generate key:
     SecureRandom → 32 bytes → Base64.getUrlEncoder().withoutPadding().encodeToString()
     → 43-char base64url string, no padding

5. Attempt to write ~/.claudony/encryption-key:
     Write key to file
     Set POSIX permissions rw------- (skip gracefully on non-POSIX filesystems)
     If write succeeds → log INFO banner, cache, return
     If write fails → log WARN ("sessions will not survive restart"), cache, return
```

### Key format

- 32 random bytes from `java.security.SecureRandom`
- Encoded as base64url without padding: 43 characters
- Example: `tX7kR2mN9pQ4vW1sL8jH6bF0cE3dA5yZ_uI-oK`
- This is a stronger key than the previous hardcoded value (256 bits of entropy vs. a manually
  chosen string of unknown entropy)

### File security

| Attribute | Value |
|---|---|
| Path | `~/.claudony/encryption-key` |
| POSIX permissions | `rw-------` (600) — owner read/write only |
| Contents | Raw base64url key, no newline, no metadata |
| On non-POSIX filesystems | File written without chmod; WARN logged |

The `~/.claudony/` directory itself is created by `ServerStartup.ensureDirectories()` on server
startup. `EncryptionKeyConfigSource` runs before `ServerStartup` (it's consulted at config
bootstrap), so it creates the directory itself if needed. Idempotent — no conflict if
`ServerStartup` also tries to create it later.

### First-run log banner

On key generation, log at `INFO` level:

```
================================================================
CLAUDONY — Session Encryption Key Generated (first run)
  Saved to: /Users/you/.claudony/encryption-key
  Permissions: rw------- (owner read/write only)

  Sessions will now survive server restarts.

  To use a custom key (e.g. from a secrets manager):
    export QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY=<your-key>
  The env var takes precedence over the persisted file.
================================================================
```

On subsequent runs, log at `DEBUG` level: "Loaded session encryption key from ~/.claudony/encryption-key"

On degraded mode (file write failed), log at `WARN` level:
"Could not persist session encryption key to ~/.claudony/encryption-key — sessions will not
survive server restarts. Check directory permissions."

---

## Native Image Support

Two steps required:

1. **`@RegisterForReflection`** on `EncryptionKeyConfigSource` — ensures the class is included
   in the native image and can be instantiated at runtime.

2. **`META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource`** — Quarkus processes
   this file at native build time. The implementation class is registered for runtime instantiation
   via ServiceLoader. No additional `reflect-config.json` entry needed; `@RegisterForReflection`
   handles it.

---

## Testing

### `EncryptionKeyConfigSourceTest` — plain JUnit, no `@QuarkusTest`

Testing outside the container is faithful to how ConfigSources actually run (before CDI).
All tests inject a temp directory to avoid touching `~/.claudony/`.

| Test | Assertion |
|---|---|
| `firstRun_generatesAndPersistsKey` | `getValue()` returns non-null 43-char base64url string; `encryption-key` file created in temp dir |
| `firstRun_fileHasCorrectPermissions` | file permissions are `rw-------` (skipped on non-POSIX) |
| `idempotent_sameKeyReturnedTwice` | two calls return identical value; file written exactly once |
| `subsequentRun_loadsExistingKey` | pre-write a known key to temp file; new source instance reads it back exactly |
| `unknownPropertyReturnsNull` | `getValue("some.other.property")` returns `null` |
| `unwritableDir_returnsKeyAndDoesNotCrash` | point source at unwritable dir; `getValue()` still returns non-null (degraded mode) |

**Total new tests: ~6.** CLAUDE.md test count updates from 139 → ~145.

---

## Files Changed

| File | Change |
|---|---|
| `src/main/java/dev/claudony/config/EncryptionKeyConfigSource.java` | **New** — ConfigSource implementation |
| `src/main/resources/META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource` | **New** — ServiceLoader registration |
| `src/main/resources/application.properties` | Remove `%prod.quarkus.http.auth.session.encryption-key` line |
| `src/test/java/dev/claudony/config/EncryptionKeyConfigSourceTest.java` | **New** — unit tests |
| `CLAUDE.md` | Update test count; update comment about prod key |
| `docs/DESIGN.md` | Add section on session key management |

---

## What This Does NOT Change

- Dev and test profile keys remain hardcoded in `application.properties` — no disruption to the
  development workflow.
- The `QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` env var override still works and takes precedence.
- `ApiKeyService` (the agent→server API key) is unaffected.
- Existing sessions will be invalidated once on the first restart after this change is deployed,
  because the new auto-generated key differs from the old hardcoded one. Users will need to log in
  once. This is expected and acceptable — it's the cost of rotating to a secure key.
- On subsequent restarts, sessions persist normally (key read from file, unchanged).

---

## Security Note on Git History

The old hardcoded key (`XrK9mP2vLq8nT5wY3jH7bN4cF6dA1eZ0`) will remain in git history after
this change. It should be considered permanently compromised and must never be reused. The new
per-deployment key is generated at runtime and never committed.

If the old key needs to be scrubbed from git history (e.g. before making the repo public), use
`git filter-repo` — but that rewrites history and should be a deliberate, coordinated operation.
