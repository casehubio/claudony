---
layout: post
title: "Claudony — The Terminal Rendering Saga"
date: 2026-04-04
---

## What We Were Trying To Achieve

Plan 3 was the frontend: the management dashboard, the xterm.js terminal
view, the iPad key bar, and the PWA manifest. The goal was a working browser
interface — open a URL, see your Claude sessions, click one, get a terminal.
Installable on iPad home screen.

No build tools, no npm, no bundler. Vanilla HTML, CSS, and JavaScript served
as static files by Quarkus from `META-INF/resources/`. xterm.js and its
addons loaded from jsDelivr CDN. The simplest possible frontend stack that
produces a usable result.

## What We Believed Going In

We expected the dashboard to be straightforward — fetch sessions from the
REST API, render cards, wire up create and delete. We expected the terminal
to be slightly harder but mostly a question of correctly wiring xterm.js to
the WebSocket via `AttachAddon`. We expected the PWA to be trivial — a
manifest.json and a service worker.

The thing we were explicitly uncertain about: iPad keyboard. We knew we
needed a custom key bar for Escape, Ctrl+C, Tab, and other keys absent from
the software keyboard. We didn't know if it would feel good in practice.

What we were not uncertain about: once the WebSocket was connected and
xterm.js was attached, the terminal would work. This turned out to be wrong.

## What We Tried and What Happened

**The dashboard** worked essentially as designed. Fetch `/api/sessions` on
load, render cards with status badges, auto-refresh every five seconds, a
dialog for creating new sessions, delete with confirmation. The CSS uses
custom properties for a dark theme that matches terminal aesthetics. Cards
show session name (stripping the `remotecc-` prefix), status badge, working
directory, and time since last activity. Responsive grid that works on iPad
in portrait and landscape.

**The PWA** was indeed trivial: `manifest.json` with `display: standalone`,
SVG icons (a `>_` symbol on a blue background), a minimal service worker
that handles install and activate. Chrome DevTools confirmed it was
installable. Safari on iPad confirmed it installs and opens full-screen
without browser chrome.

**The terminal — first problem: "not a terminal".** Opening a terminal view
connected the WebSocket but xterm.js displayed: "open terminal failed: not
a terminal". The WebSocket itself was working fine — it was faithfully
forwarding tmux's error message. As we noted when building the Server, `tmux
attach-session` requires a real PTY; ProcessBuilder provides pipes. tmux
refuses to run and that refusal is what the user sees.

The fix was to abandon `tmux attach-session` entirely. The replacement
architecture:

```
tmux pane output
  → pipe-pane spawns: /bin/sh -c "cat > /tmp/remotecc-{connid}.pipe"
  → cat writes to named FIFO
  → Java virtual thread reads from FIFO via FileInputStream
  → sendTextAndAwait(chunk) → xterm.js
```

`tmux pipe-pane` streams pane output to any shell command — no PTY required.
We create a named FIFO (a Unix pipe file), start a virtual thread that opens
it for reading (blocking until a writer connects), then start pipe-pane
which runs `cat > /tmp/remotecc-{connid}.pipe`. Both sides block on the
FIFO open, then unblock each other — standard POSIX FIFO semantics.

For input, `tmux send-keys -t sessionName -l "text"` with the `-l` flag for
literal text. This handles regular characters, control codes like `\x03`
(Ctrl+C), and escape sequences like `\x1b[A` (up arrow). One `ProcessBuilder`
spawn per keystroke — slightly slower than direct stdin writes would be, but
fast enough for interactive use.

**The hot-reload trap.** After committing the pipe-pane implementation,
Quarkus hot-reloaded. Subsequent WebSocket connections returned HTTP 101
(handshake success) but `@OnOpen` never fired. No log entries. REST endpoints
continued working normally. Quarkus WebSockets Next's endpoint registration
does not survive hot-reload in dev mode reliably. The only fix is a full
server restart. This happened multiple times during the session and became
the single most disruptive dev-mode quirk — enough to document prominently
and communicate as a workflow rule.

**The terminal history saga.** This was where we spent the most time, and
where the iterations were humbling.

When you navigate away from a terminal view and return, the WebSocket
reconnects but you're looking at a blank terminal. The session is alive, you
can type, but you've lost visual context. We wanted to replay recent terminal
history on reconnect — the last 100 lines of what was visible in the session.

`tmux capture-pane -p` seemed like the answer. First attempt produced severe
indentation. tmux's capture-pane pads every line to the full pane width with
spaces — if the pane is 200 columns wide, every line is padded to 200
characters. Send these to xterm.js and the cursor ends up at column 200;
everything after appears there too.

Strip trailing whitespace from each line: indentation fixed. Now leading
blank rows appear — a fresh session has the first command on row 0 of the
grid, then 20 empty rows, then the actual output.

Strip leading blank rows: blank rows still appear inside the content (the
empty pane rows between early commands and recent output).

Collapse consecutive blank rows to a single blank: the text "ash" started
appearing on reconnect instead of "bash". This was a race condition. The
virtual thread (sending pipe-pane output) and the main `@OnOpen` thread
(sending capture-pane history) were both calling `sendTextAndAwait()`
concurrently. pipe-pane occasionally flushed one byte before history was
sent, arriving first in xterm.js and shifting everything by one character.

Fix the race: send history synchronously before creating the FIFO and
starting pipe-pane. History always arrives first. The race is structural —
you cannot fix it by timing adjustments, only by sequencing.

Switch to `capture-pane -e` (with ANSI escape sequences): colours preserved
from the original terminal. Check for blank lines by stripping ANSI codes
first, then checking if empty after stripping. Join history lines with
`\r\n` between them, not after the last — the cursor stays at the end of the
last line (the current prompt) rather than jumping to a new line.

Then: `bash-3.2$ls` on first keystroke — missing space after the prompt.
The prompt line ends with `$ ` which `stripTrailing()` converts to `$`.
The user types `ls` and it appears at the `$` position: `bash-3.2$ls`. Fix:
restore exactly one trailing space to the last content line. It's always the
current prompt. Cursor lands after `$ `.

Seven commits. Each one fixed a real problem and revealed the next. The
final state: history replays cleanly, no indentation, no blank lines from
grid artefacts, colours preserved, consistent cursor position, consistent
between first connect and reconnect.

One cosmetic issue remains: a single blank line appears below the prompt
when first connecting. This is pipe-pane's `cat` flushing a `\r\n` when it
first connects to the FIFO. It's harmless — the user's first keystroke
overwrites it. Fixing it would require intercepting and discarding the first
bytes from pipe-pane specifically, which isn't worth the complexity.

**The iPad keyboard.** The key bar works. Eleven buttons (Esc, Ctrl+C,
Ctrl+D, Tab, backtick, pipe, tilde, up, down, left, right) appear
automatically on touch devices via `navigator.maxTouchPoints > 0`. Each
button sends raw bytes to the WebSocket. Functional, not elegant — you have
to switch between typing and tapping. Acceptable for a first version.

## What Changed and Why

The `tmux attach-session` approach was abandoned completely — expected in
hindsight, but the discovery came from the live smoke test rather than
design review. The pipe-pane/FIFO architecture is more complex but works
without any native libraries and compiles cleanly to a native binary.

The history replay took seven commits instead of the expected one or two.
Each fix was correct in isolation; the interactions between them are what
required iteration. The race condition in particular was invisible in tests
because `@QuarkusTest` doesn't run concurrent `sendTextAndAwait()` calls on
the same connection.

## What We Now Believe

The frontend works. The dashboard is clean, session management works, the
terminal connects and streams correctly, history replays on reconnect, the
PWA installs on iPad, and typing works with the key bar.

The pipe-pane/FIFO architecture is the right one given our constraints. No
PTY library, no native code, GraalVM-native-image compatible. The tradeoff
is one `ProcessBuilder` spawn per keystroke (for `send-keys`) and the FIFO
setup overhead on connection — both are imperceptible in practice.

For TUI applications — Claude Code itself, vim, htop — the `capture-pane -e`
visual snapshot is imperfect at different terminal dimensions. The correct
path is a resize event from xterm.js, which triggers SIGWINCH and causes TUI
apps to redraw at the new size. This works but requires the user to be in
the terminal view when the resize fires.

The Quarkus hot-reload issue is our biggest remaining dev friction. The
native binary doesn't have it, so it's a dev-mode problem only. But during
active development, every Java commit triggers it.

What we haven't tested yet: actually running the controller Claude against
the MCP server and using it to manage sessions through conversation. That's
the whole point of the system. It's the thing we built everything towards
and haven't validated end-to-end.

---

**Next:** Connecting the controller Claude, validating the MCP tools against
real sessions, and finding out whether the system we imagined on day zero
actually works as a daily workflow.
