# 0001 — Terminal Streaming Strategy

Date: 2026-04-14
Status: Accepted

## Context and Problem Statement

Claudony streams live terminal output from tmux sessions to browser clients via
WebSocket. `tmux attach-session` requires a real PTY, which Java's `ProcessBuilder`
cannot provide. A streaming mechanism was needed that works within the JVM process
model and remains GraalVM-native compatible.

## Decision Drivers

* Java `ProcessBuilder` cannot allocate a PTY — `tmux attach-session` fails without one
* Must work in GraalVM native image (no JNI PTY libraries)
* Low latency: output must appear in the browser as it is produced
* Input must reach the tmux pane faithfully (special characters, escape sequences)

## Considered Options

* **Option A** — PTY library (e.g. pty4j) to attach to tmux directly
* **Option B** — `tmux pipe-pane` → FIFO → virtual thread → WebSocket
* **Option C** — `tmux capture-pane` polling on an interval

## Decision Outcome

Chosen option: **Option B** (`tmux pipe-pane` + FIFO), because it produces
a continuous output stream with no polling delay, requires no native PTY library,
and is fully compatible with GraalVM native image.

Input is sent via `tmux send-keys -t name -l "text"` (the `-l` literal flag is
critical — it prevents tmux from interpreting special characters).

### Positive Consequences

* No PTY library dependency — stays GraalVM-native compatible
* True streaming: pane output flows immediately to the browser
* Input path is simple and reliable via `send-keys -l`

### Negative Consequences / Tradeoffs

* `pipe-pane` cannot be used with `tmux attach-session` simultaneously — the FIFO
  approach is the only output channel
* History replay on reconnect requires `tmux capture-pane -e -p -S -100` separately,
  with careful cursor positioning to avoid duplicate prompts (see BUGS-AND-ODDITIES.md)
* TUI apps (Claude Code, vim) replay imperfectly — terminal resize triggers correct redraw

## Pros and Cons of the Options

### Option A — PTY library (pty4j)

* ✅ True terminal attach — full TUI support
* ❌ JNI dependency breaks GraalVM native image
* ❌ Adds a significant native dependency with platform-specific binaries

### Option B — tmux pipe-pane + FIFO (chosen)

* ✅ No native dependencies
* ✅ GraalVM native compatible
* ✅ Low latency streaming
* ❌ History replay requires extra work and has edge cases

### Option C — tmux capture-pane polling

* ✅ Simplest implementation
* ❌ Polling interval introduces visible latency
* ❌ Missed output between polls if pane scrolls

## Links

* `docs/BUGS-AND-ODDITIES.md` — terminal streaming edge cases and quirks
* `src/main/java/dev/claudony/server/TerminalWebSocket.java` — implementation
