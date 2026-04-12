# Handover — 2026-04-07 (session 2)

**Head commit:** `30ebb9d` — docs: blog entry 2026-04-07-03
**Previous handover:** `git show 6dcb031:HANDOVER.md`

## What Changed This Session

**System is now fully working end-to-end** — first real deployment and usage.

**Auth fixed:**
- `LenientNoneAttestation` + `WebAuthnPatcher` — Apple iCloud Keychain passkeys work (Vert.x NoneAttestation rejected non-zero AAGUID; patched via reflection at startup)
- WebAuthn endpoint paths fixed in `register.html` / `login.html` (`/q/webauthn/register` + `/q/webauthn/callback`, not the nonexistent `/options`+`/finish` pattern)

**Terminal history replay fixed (3 bugs, 128 tests):**
1. Blank lines within pane content now preserved → xterm.js row N == pane row N → no duplicate prompts
2. Cursor positioning escape (`ESC[row;colH`) appended to history → typed input lands on prompt, not last content line
3. Initial `\r\n` pipe-pane FIFO flush now skipped → cursor doesn't slip one more row

**Also fixed:** `resize-pane` → `resize-window` (resize-pane silently no-ops for detached sessions)

**CLAUDE.md:** JVM jar startup commands documented (−D flags must go BEFORE −jar)

## Running State

Server and agent are running (JVM mode, not native):
```bash
# Server
JAVA_HOME=$(/usr/libexec/java_home -v 26) java -Dclaudony.mode=server -Dclaudony.bind=0.0.0.0 -jar target/quarkus-app/quarkus-run.jar

# Agent  
JAVA_HOME=$(/usr/libexec/java_home -v 26) java -Dclaudony.mode=agent -Dclaudony.port=7778 -jar target/quarkus-app/quarkus-run.jar
```
Logs: `/tmp/claudony-server.log`, `/tmp/claudony-agent.log`

## Immediate Next Step

**Session encryption key** — WebAuthn session cookies expire on server restart (random key each time). Set stable key for persistent sessions:
```bash
export QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY=<secret-32-chars>
```

After that: deploy to Mac Mini (DEPLOYMENT.md doesn't exist yet — `docs/DEPLOYMENT.md` to be written).

## Open Questions

- Session expiry: cookies are session-scoped (expire on browser close); `Max-Age` requires intercepting Quarkus internal cookie issuance — acceptable?
- Pre-1.0 ADRs: terminal streaming, MCP transport, auth mechanism choices still unwritten
- iPad access: LAN IP is `192.168.1.108:7777` — WebAuthn origin config must match if accessing from iPad

## References

| Context | Where |
|---------|-------|
| Terminal bugs detail | `docs/BUGS-AND-ODDITIES.md` entries #12–15 |
| Living design doc | `docs/DESIGN.md` |
| Latest blog | `docs/blog/2026-04-07-mdp03-terminal-was-lying-about-cursor.md` |
| Previous handover | `git show 6dcb031:HANDOVER.md` |
