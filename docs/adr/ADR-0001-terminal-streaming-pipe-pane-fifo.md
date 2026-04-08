# ADR-0001: Terminal Streaming via pipe-pane and FIFO

**Status:** Accepted  
**Date:** 2026-04-04  
**Deciders:** Mark Proctor

---

## Context

RemoteCC needs to stream live terminal output from tmux sessions to a browser over WebSocket. The server runs as a Quarkus JVM process using `ProcessBuilder` to invoke tmux commands — it does not have a real PTY available.

Three constraints shaped the decision:

1. **No PTY in the JVM.** `ProcessBuilder` cannot allocate a PTY. Methods that require one (e.g. `tmux attach-session`) receive an error: `not a terminal`.
2. **GraalVM native image.** Any solution must be native-image compatible — no JNI libraries, no dynamic native loading.
3. **tmux is the source of truth.** Sessions must persist independently of the Quarkus process. The streaming mechanism must not own the session lifecycle.

---

## Alternatives Considered

### 1. `tmux attach-session` via ProcessBuilder
Connect stdin/stdout of a child process running `tmux attach-session` to the WebSocket.

**Rejected.** `tmux attach-session` requires a real PTY — without one it prints `not a terminal` and exits. ProcessBuilder cannot provide a PTY. This was actually implemented first and discovered not to work in production.

### 2. pty4j (JetBrains PTY library)
Use pty4j to allocate a PTY from Java, then run `tmux attach-session` inside it.

**Rejected.** pty4j uses JNI extensively and bundles native `.dylib`/`.so` libraries. GraalVM native image has limited JNI support; getting pty4j working natively would require complex and fragile reflection/JNI config. Ruled out before implementation began.

### 3. `script -q /dev/null tmux attach-session`
Use macOS `script` to force PTY allocation without a Java PTY library.

**Rejected.** Platform-specific (macOS vs Linux `script` differ), fragile, and still involves managing a subprocess stdin/stdout pipeline with no clean API. The complexity wasn't justified.

### 4. pipe-pane + named FIFO (chosen)
Use `tmux pipe-pane` to route pane output to a named FIFO; read the FIFO from a Java virtual thread.

**Accepted.**

---

## Decision

Use `tmux pipe-pane -t {name} "cat > /tmp/remotecc-{id}.pipe"` to redirect pane output to a named FIFO. A Java virtual thread opens the FIFO for reading (blocking until the writer connects) and forwards bytes to the WebSocket. Input goes via `tmux send-keys -t {name} -l "{text}"`.

The `-l` (literal) flag on `send-keys` is required — without it, tmux interprets key names in the text string (e.g. the word "Enter" becomes a newline).

**History replay on reconnect:** `tmux capture-pane -e -p -S -100` snapshots the last 100 lines of scrollback with ANSI colour codes. This is sent synchronously before pipe-pane starts to eliminate a race condition where live output would arrive before the snapshot.

---

## Consequences

**Positive:**
- No PTY dependency — works from any JVM process, native-image compatible
- tmux owns session lifecycle; Quarkus is just an observer
- Virtual threads make the blocking FIFO read cheap

**Negative:**
- `pipe-pane` sends an initial `\r\n` flush when it first connects to the FIFO; this must be swallowed to avoid moving the cursor
- History replay for TUI apps (vim, Claude Code) requires a resize-window trick to force a redraw before capture — the captured state of an alternate-screen app in a detached session is otherwise incorrect
- One FIFO file per active WebSocket connection; cleanup must run on both `@OnClose` and `@OnError`

**See also:** `docs/BUGS-AND-ODDITIES.md` entries #1 (hot-reload), #3 (TUI history), #5 (pipe-pane flush)
