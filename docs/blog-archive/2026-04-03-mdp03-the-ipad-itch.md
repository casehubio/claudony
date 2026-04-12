# Claudony — The iPad Itch

**Date:** 2026-04-03
**Type:** day-zero

---

## What We Were Trying To Achieve

I work with Claude Code CLI every day on my MacBook. The problem is that I
also have a large iPad Pro and I want to be able to continue working on it —
switching devices mid-session without losing context, or just picking up the
iPad when I'm away from my desk. This project starts from that itch: get
Claude Code CLI sessions accessible from the iPad, and keep them alive
regardless of which device I'm using.

## What We Believed Going In

The obvious answer is SSH and tmux. You run tmux on the MacBook, SSH in from
the iPad using Blink Shell, attach to the session. This is how developers
have been sharing terminal sessions for decades and it works. It requires:
setting up SSH on the MacBook, installing a good terminal app on the iPad,
configuring SSH keys, remembering which session name to attach to. Friction,
but manageable.

We also started with the assumption that "remote" means iPad specifically.
This is the device I'm thinking about, so it's where the design should
start.

And we assumed this would be a quick infrastructure setup problem — a few
shell scripts to manage tmux sessions, maybe an alias or two. Something we
could put together in an afternoon.

## What We Tried and What Happened

We laid out the SSH/tmux approach: enable Remote Login on the Mac, install
tmux, install Tailscale for cross-network access, install Blink Shell on the
iPad. It works. If the goal were just "SSH from iPad into Mac and attach to
tmux", we'd be done.

But sitting with that solution for a few minutes revealed what was actually
missing. The question "where do I type the command?" opened up something
more interesting. The vision that emerged had three parts:

First, a **controller Claude** — a Claude Code instance running in its own
iTerm2 window with MCP tools that give it awareness of all other Claude
sessions. It can create new sessions, know about running ones, interact with
them.

Second, a **management interface** — something you can open on any device
and see all your active Claude sessions, start new ones, navigate between
them without memorising session names or typing tmux commands.

Third, **session mirroring** — the same session should be viewable from
your MacBook's iTerm2 AND a browser on the iPad simultaneously, both seeing
the same terminal.

The SSH/tmux approach handles the third part but not the first two. So we
started asking: what would it take to build all three?

## What Changed and Why

The scope grew from "shell script to manage sessions" to something
considerably more ambitious. The pivot point was realising that "which device
do I type the command on?" is actually the wrong question. The right question
is "can I manage Claude sessions from any device, without knowing or caring
about the underlying infrastructure?"

That reframe changes the problem completely. Now we're building a system
with a controller Claude at the centre, a web management interface, and
WebSocket-based terminal access from any browser. SSH and Blink Shell drop
out entirely — you don't need a specialised terminal app if you can connect
via any browser.

We also dropped the assumption that "remote" means iPad only. A headless Mac
Mini becomes an interesting option — run the sessions there, access from
MacBook and iPad alike.

## What We Now Believe

This is a real project, not an afternoon of config. The right way to build
it is a Quarkus application (given familiarity with the stack) serving two
modes: a **Server** that owns tmux sessions and streams terminal output over
WebSocket, and an **Agent** that provides MCP tools for the controller Claude
and handles local automation like opening iTerm2 windows.

tmux is the right source of truth for session state — it already handles
session persistence, and all our streaming needs to do is proxy the pane
output. We don't need a PTY library, just ProcessBuilder piping to tmux.

The frontend will be a PWA — installable on iPad home screen, full-screen,
no App Store required. The web is the right delivery mechanism for something
that needs to work on multiple devices with different form factors.

We don't know yet how to handle the terminal output streaming cleanly, how
the iPad keyboard limitation will play out, or whether capture-pane history
replay will work well enough in practice. Those are the real technical risks.

---

**Next:** The brainstorming session that turned a vague vision into an
architecture — and the research that showed what already existed and what
we'd need to build ourselves.
