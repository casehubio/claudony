# Claudony — The Dashboard Grew Up (And the Compose Took Five Tries)

**Date:** 2026-04-09
**Type:** phase-update

---

## What I was trying to achieve: more signal on the session card

The session cards were functional but sparse — name, status, working directory.
I wanted them to answer the questions I actually ask when I glance at a project:
is the PR open? Did CI pass? Is the dev server running?

I brought Claude in and we added three things: GitHub PR/CI status fetched on
demand via `gh pr view`, service health badges via TCP connect to common dev
ports, and an "Open in iTerm2" button that appears only when accessing from
localhost. The cards now carry real operational signal.

Those three went in cleanly. The fourth — a compose overlay for working around
Claude Code's missing click-to-position cursor support — did not.

## What I believed going in: sending text would be the easy part

The compose idea came from a thread about Claude Code's Ink input limitation —
the input component doesn't handle mouse click events for cursor repositioning,
and it won't until Anthropic fixes it upstream. The workaround: a browser
textarea with full mouse editing, forwarding the composed text to the terminal.

I thought the hard part would be the UI. The UI took twenty minutes. Sending
the text took the rest of the session.

## The Compose That Wouldn't Compose

We built the overlay and it opened fine. Composing text worked. Clicking Send
did nothing. No text in the terminal, no error.

**Round 1:** `ws.send(text)` — the same mechanism as the key bar buttons. Silent failure.

**Round 2:** Switch to `POST /api/sessions/{id}/input` via `fetch()`. Still nothing.
No error, no response, no text.

**Round 3:** I asked Claude to add `console.log` statements to trace what was
happening. Nothing appeared in the browser console even after clicking Send —
not even the log at the very first line of the function.

At this point I said: "there is still nothing being sent, can you have a deep thought
about the root cause."

Claude came back with two things. First: static files are embedded in the JAR at
build time. We had been restarting the server without rebuilding — so the
`console.log` statements existed in the source tree but not in what the browser was
actually running. We rebuilt. The logs appeared.

Second: `ws.send()` bypasses the AttachAddon pipeline. The `@xterm/addon-attach`
addon wires `terminal.onData → ws.send()` for all terminal input. Direct
`ws.send()` calls from outside that pipeline skip bracketed paste mode
(`\x1b[200~...\x1b[201~`), which Claude Code's Ink input enables. After a
reconnect, the `ws` reference you hold may also be stale. The correct path is
`terminal.paste(text)` — it fires `onData`, which AttachAddon forwards correctly.

**Round 4:** `terminal.paste(text)`. Text appeared.

**Round 5:** I'd also added Ctrl+A + Ctrl+K to clear the prompt before inserting.
The clear raced with the paste — the text arrived before tmux processed the clear.
The question came back: "it inserts wherever the cursor is, all other text shifts —
is this correct?" It is. Insert-at-cursor is standard text editor behaviour, which
is exactly right. We removed the clearing entirely.

## What it is now

The dashboard has real content on each session card. PR/CI status, service health,
and iTerm2 access appear on demand. The compose overlay works — Ctrl+G or a header
button, full browser textarea with mouse editing, Ctrl+Enter sends.

One non-obvious thing surfaced during the REST debugging: Quarkus WebAuthn generates
a new random session encryption key on every restart when
`QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY` isn't set. An open WebSocket survives
a restart, but new HTTP requests get a silent 401 because the cookie was encrypted
with the previous key. That's what was swallowing the `fetch()` calls in rounds 2
and 3. Set the env var and it stops.
