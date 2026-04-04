# RemoteCC — Bugs, Fixes, and Behavioural Oddities

Accumulated during initial development (2026-04-03/04). Intended to help future
Claude sessions diagnose regressions quickly and understand non-obvious system
behaviours.

---

## 1. Quarkus WebSockets Next — Hot-Reload Breaks Endpoint Registration

**Symptom:** After committing Java code in dev mode (triggering a hot reload),
WebSocket connections return HTTP 101 (handshake succeeds) but `@OnOpen` is
never called. No DEBUG log entries appear for WebSocket events. REST endpoints
continue to work normally.

**Root cause:** Quarkus WebSockets Next re-registers WebSocket endpoints at
startup. In dev mode, hot-reload restarts the application but does not always
correctly re-register the WebSocket endpoint handler at the Vert.x level. The
HTTP upgrade succeeds (Vert.x handles it) but the application-level `@OnOpen`
handler is never wired up.

**Diagnosis:**
```bash
# Test if @OnOpen is firing — check log for DEBUG entries
grep "WebSocket open" /tmp/remotecc.log

# Confirm WebSocket upgrade still works at HTTP level (101 = success)
python3 -c "import socket, base64; s=socket.socket(); s.connect(('localhost',7777)); key=base64.b64encode(b'test').decode(); s.send(f'GET /ws/ID HTTP/1.1\r\nHost: localhost:7777\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n'.encode()); print(s.recv(256)[:50])"
# If you get HTTP/1.1 101 but no @OnOpen log → hot-reload broken WebSocket
```

**Fix:** Full server restart (not hot-reload):
```bash
pkill -f "quarkus:dev"; sleep 2
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -Dremotecc.mode=server ...
```

**Rule:** After committing any Java changes that touch WebSocket classes during
an active dev session, always do a full restart. Hot-reload is unreliable for
WebSocket endpoints in Quarkus WebSockets Next.

---

## 2. tmux attach-session Requires a Real PTY

**Symptom:** Browser receives the text "open terminal failed: not a terminal"
in the xterm.js display. WebSocket connects successfully.

**Root cause:** `tmux attach-session` calls `isatty()` on its file descriptors.
Java's `ProcessBuilder` provides pipes (not pseudo-terminals), so tmux refuses
to run and outputs the error, which is then sent to xterm.js.

**Fix:** Do not use `tmux attach-session` for WebSocket streaming. Instead use
`tmux pipe-pane` (no PTY required):
```java
// WRONG — requires PTY
new ProcessBuilder("tmux", "attach-session", "-t", sessionName)

// CORRECT — PTY-free, reads pane output via pipe
new ProcessBuilder("tmux", "pipe-pane", "-t", sessionName, "cat > " + fifoPath)
```

**See also:** Entry #3 (FIFO-based streaming architecture).

---

## 3. pipe-pane + FIFO Architecture — How It Works and What Can Go Wrong

**Architecture:**
```
tmux pane output
    → pipe-pane spawns: /bin/sh -c "cat > /tmp/remotecc-{connid}.pipe"
    → cat writes to FIFO
    → Java virtual thread reads from FIFO via FileInputStream
    → connection.sendTextAndAwait(chunk) → xterm.js
```

**FIFO behaviour:** Opening a FIFO blocks BOTH sides until both reader and writer
are ready. Java's `FileInputStream(fifoPath)` blocks until cat connects for
writing; cat's `open(O_WRONLY)` blocks until Java opens for reading. POSIX
guarantees they unblock each other simultaneously. This is expected — not a
deadlock.

**pipe-pane status check is misleading:** `tmux show-options -t name | grep pipe`
shows "not active" while cat is still blocked waiting for the FIFO reader. Once
both sides connect, the pipe becomes active. Do not use this check to diagnose
failure immediately after starting pipe-pane.

**Cleanup:** Always call `tmux pipe-pane -t name` (with no command argument) to
stop pipe-pane, and `Files.deleteIfExists(Path.of(fifoPath))` to remove the FIFO.
On Quarkus dev-mode hot-reload, the in-memory cleanup maps (`fifoPaths`,
`sessionNames`) are reset before `@OnClose` fires for existing connections,
leaving orphan FIFOs in `/tmp/remotecc-*.pipe`. These are harmless but can
accumulate. Clean with `rm /tmp/remotecc-*.pipe`.

**Stopping pipe-pane:** `tmux pipe-pane -t name` (no shell command) stops any
active pipe for that pane.

---

## 4. Race Condition: pipe-pane Flushing Before History Replay

**Symptom:** On reconnect, the first word of the first history line is missing a
character — e.g., "ash" instead of "bash". The exact character lost varies.

**Root cause:** Both the virtual thread (reading FIFO → sending to WebSocket) and
the `@OnOpen` thread (sending capture-pane history) call `sendTextAndAwait()`
concurrently. pipe-pane occasionally flushes a byte to the FIFO before the
history is sent, causing interleaving on xterm.js.

**Fix:** Send the capture-pane history synchronously BEFORE setting up the FIFO
and starting pipe-pane. This guarantees history is displayed first, with no
concurrent writers:
```java
// CORRECT ORDER in @OnOpen:
// 1. capture-pane → send history (synchronous, no FIFO yet)
// 2. mkfifo
// 3. start virtual thread (opens FIFO, blocks)
// 4. start pipe-pane (cat connects to FIFO, unblocks virtual thread)
```

---

## 5. capture-pane Output — Padding, Blank Lines, and Colour

**`capture-pane -p` (plain text):**
- Every line is padded with spaces to the full pane width (e.g., 200 chars)
- Sending this directly to xterm.js pushes the cursor to column 200
- Subsequent pipe-pane output starts at column 200 → severe indentation

**`capture-pane -e` (with ANSI escape sequences):**
- Preserves colours from the original terminal session
- Lines are STILL padded to pane width with trailing spaces
- Use this for the history replay (better user experience)

**Processing capture-pane output correctly:**
1. Split by `\n`
2. To detect blank lines: strip ANSI codes first (`replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "")`), then check if empty
3. For each non-blank line: use `line.stripTrailing()` to remove trailing padding spaces
4. Join lines with `\r\n` between them — NOT after the last line
5. Add one trailing space to the last line (the current prompt) to restore the `$ ` cursor position
6. Append `\x1b[0m` (SGR reset) after history to clean terminal state

**Why not trim the last line:** The last line is always the current prompt (e.g.,
`bash-3.2$ `). After `stripTrailing()`, the space after `$` is gone. Without
restoring it, the cursor sits right after `$`, and user input appears as
`bash-3.2$ls` (missing space). Add `" "` back to the last content line.

**Blank lines in pane grid:** tmux's capture-pane returns the full pane grid
including empty rows. A fresh session will show the initial command on row 0,
then ~20 empty rows, then the actual output. Strip all blank lines (after ANSI
removal) — they are grid artifacts, not real output.

---

## 6. Jackson Deserialization for Quarkus REST Client

**Symptom:** MCP `list_sessions` tool returns error:
```
"Response could not be mapped to type java.util.List<SessionResponse>
for response with media type application/json. Hints: Consider adding
quarkus-rest-client-reactive-jackson or quarkus-rest-client-reactive-jsonb"
```

**Root cause:** `quarkus-rest-client` alone does not include Jackson for JSON
deserialisation of REST responses. The dependency that provides it is separate.

**Fix:** Add to `pom.xml`:
```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-rest-client-reactive-jackson</artifactId>
</dependency>
```

Note: Despite the name having "reactive", this is the correct extension for the
non-reactive `quarkus-rest-client` when using the `quarkus-rest` stack.

---

## 7. Java Version Requirements

**Virtual threads** (`Thread.ofVirtual()`) require Java 21+. The project targets
Java 21 via `maven.compiler.release=21`.

**Java 26 is fine:** Java 26 is installed and used as the JVM. Compiling with
`release=21` on Java 26 works correctly — it targets the Java 21 API surface
while running on the Java 26 JVM.

**The maven wrapper (`./mvnw`) was broken** on this machine — its first line
contained garbage. Use system `mvn` directly:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test
```

---

## 8. GraalVM Is Installed via SDKMAN, Not on PATH

**Symptom:** `native-image --version` says "not found" even though GraalVM is installed.

**Location:** `~/.sdkman/candidates/java/25.0.2-graalce/bin/native-image`
Also at: `/Library/Java/JavaVirtualMachines/graalvm-25.jdk/`

**Use for native builds:**
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn package -Pnative -DskipTests
```

**Available Java versions on this machine:**
- 26 (Oracle, default) — `/Library/Java/JavaVirtualMachines/jdk-26.jdk`
- 25 (GraalVM CE) — `~/.sdkman/candidates/java/25.0.2-graalce`
- 25 (OpenJDK) — `~/Library/Java/JavaVirtualMachines/openjdk-25.0.1`
- 19 (Amazon Corretto)
- 17 (Homebrew OpenJDK)
- 11 (Homebrew OpenJDK)

---

## 9. Native Binary Must Be Rebuilt After Adding New Endpoints

**Symptom:** Endpoints added in Plan 2 (e.g., `POST /api/sessions/{id}/input`)
return HTTP 404 when using the native binary, but work correctly in JVM mode.

**Root cause:** The native binary was compiled during Plan 1. New endpoints added
later are not included until the binary is recompiled.

**Rule:** Always rebuild the native binary after adding or modifying REST
endpoints:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn package -Pnative -DskipTests
```

---

## 10. Quarkus Dev Mode Log May Stop After Hot-Reload

**Symptom:** Redirected log file (`mvn quarkus:dev > /tmp/remotecc.log 2>&1 &`)
stops receiving new entries after a hot-reload. REST endpoint logs still work
but WebSocket DEBUG logs disappear.

**Diagnosis:** Check if log is still receiving entries by making a REST call and
checking line count:
```bash
BEFORE=$(wc -l < /tmp/remotecc.log)
curl -s -X POST http://localhost:7777/api/sessions -H "Content-Type: application/json" \
  -d '{"name":"test","workingDir":"/tmp","command":"bash"}' > /dev/null
sleep 1
AFTER=$(wc -l < /tmp/remotecc.log)
echo "New lines: $((AFTER - BEFORE))"
```
If REST calls produce log entries but WebSocket events do not → hot-reload broke
the WebSocket endpoint (see Entry #1).

---

## 11. TUI Applications (Claude Code, vim, etc.) — Limited History Replay Support

**Behaviour:** When a session is running a TUI application (Claude Code, vim,
htop, etc.) and you reconnect, the history replay via `capture-pane -e` sends
a visual snapshot of the current screen. This works but is imperfect:

- The snapshot is a grid at the ORIGINAL pane dimensions, not the new terminal size
- Escape sequences in the snapshot reference absolute cursor positions that may
  be wrong at different terminal dimensions
- After reconnect, trigger a terminal resize (the browser's resize observer calls
  `/api/sessions/{id}/resize`) which sends SIGWINCH to the process and causes
  TUI apps to redraw at the correct size — this is the correct path to clean display

**Workaround for forced redraw:** After reconnecting to a TUI session, press
Ctrl+L (if the app supports it) or trigger a resize by resizing the browser window.

---

## 12. One Blank Line After Prompt on Terminal Connect

**Symptom:** When opening or reconnecting to a terminal, there is one blank line
visible after the current prompt. This happens consistently.

**Root cause:** pipe-pane connects and cat flushes an initial `\r\n` from the
pane's pending output buffer when the FIFO connection is first established.
This newline appears in xterm.js as a blank line after the history replay.

**Status:** Cosmetic only. The terminal functions correctly. The blank line
disappears as soon as the user types (new output overwrites it). Fixing it
would require intercepting the first bytes from pipe-pane before sending to
xterm.js, which adds complexity for minimal gain.

---

## 13. Tmux Session Bootstrap Survives Server Restart

**Behaviour (correct):** When the Quarkus server restarts, `ServerStartup`
calls `tmux list-sessions` and re-imports sessions with the `remotecc-` prefix
back into the in-memory registry. Sessions continue running in tmux regardless
of server state.

**Edge case:** Sessions created before a restart have their `workingDir` set to
`"unknown"` in the bootstrapped registry entry (tmux does not expose the original
working directory). The actual session still works correctly.

**Gotcha:** Bootstrap runs at startup. Sessions created AFTER the previous server
started but before it was killed WILL be picked up. Sessions created before any
`remotecc-` prefix was configured (e.g., manually created tmux sessions) will NOT
be picked up.

---

## 14. `tmux send-keys -l` for Raw Input

When forwarding WebSocket text input to tmux, use `-l` (literal) flag:
```bash
tmux send-keys -t sessionName -l "text"
```

Without `-l`, tmux interprets certain strings as key names (e.g., "Enter",
"Escape") rather than literal text. With `-l`, all text including control
characters (`\x03` for Ctrl+C, `\x1b[A` for up arrow) is sent as raw bytes.
Available in tmux 3.2+.

---

## 15. Clipboard in tmux Requires Configuration

**Symptom:** Copy/paste between the browser terminal and macOS clipboard does
not work.

**Fix:** Add to `~/.tmux.conf`:
```
set -g set-clipboard on
```
Then reload: `tmux source-file ~/.tmux.conf`

This uses the OSC 52 clipboard protocol, which is supported by xterm.js via
`@xterm/addon-clipboard`. The Agent startup checks for this and offers to
auto-fix.

Required tmux version: 3.2+ (tmux 3.6a is installed on this machine).
