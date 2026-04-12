# Claudony — From SSH to PWA

**Date:** 2026-04-03
**Type:** phase-update

---

## What We Were Trying To Achieve

Having established the vision, we needed an architecture. The goal of this
session was to get from "here's the idea" to a concrete design with enough
detail that implementation could actually start. This meant answering the
hard questions: what technology runs on which machine, how does the iPad
connect, what does the controller Claude actually do, and what does the
web UI look like.

## What We Believed Going In

We came in believing the hard parts were the frontend (what technology for
the iPad UI?) and the MCP integration (how does the controller Claude talk
to the system?). We expected the session management and streaming pieces to
be relatively straightforward once we'd picked the right libraries.

We also came in with the assumption that the iPad would need a dedicated
terminal app. Even with a web-based management interface, we assumed
actually running a terminal in the browser would require something like
Blink Shell for the real terminal work.

## What We Tried and What Happened

The brainstorming session produced a series of design questions, each of
which forced a decision.

**WebSocket vs SSH for remote access.** SSH is the obvious answer for
remote terminal access, but it puts the burden on every client device:
SSH keys, terminal apps, knowing the hostname. WebSocket through a web
server means any browser connects. We chose WebSocket — SSH can be added
later if a specific need arises.

**What is the "iPad terminal"?** This question collapsed when we realised
a Progressive Web App with xterm.js is a full terminal emulator in a
browser tab. You install it to the home screen, it opens full-screen, it
has no browser chrome. There's no dedicated terminal app needed. The "app"
is just a URL. This also means the same web interface works on MacBook,
iPad, another Mac, or anything with a browser.

**What existing tools cover this?** We did research and found several
projects in this space. Webmux (by Windmill) is built specifically for
parallel AI agent management — web dashboard, WebSocket terminals,
Tailscale support. Muxplex is a lighter web tmux dashboard. Various
MCP servers for tmux orchestration exist (conductor-mcp,
tmux-claude-mcp-server). None were Claude-centric enough, and none had
the controller Claude concept we wanted. We decided to build our own but
use xterm.js for the terminal component.

**The Server/Agent split.** This one emerged mid-session and turned out to
be the most important architectural decision. The naive design has one
Quarkus instance doing everything. But consider a Mac Mini headless server:
the sessions run there, but iTerm2 windows can only open on the machine
where iTerm2 is installed (the MacBook). The system needs to handle both
local and remote deployments.

The solution is two modes from the same binary. The **Server** runs on
whichever machine hosts the tmux sessions — it manages session lifecycle,
streams terminal output via WebSocket, serves the web frontend. The
**Agent** runs on the local machine (the MacBook) — it exposes the MCP
endpoint for the controller Claude, handles local automation like opening
iTerm2 windows, and proxies session commands to the Server. In a
MacBook-only setup, you run both locally. In a Mac Mini setup, Server is
remote and Agent is local.

We recognised this split was critical to get right from the start.
Retrofitting it later would be painful.

**pty4j vs ProcessBuilder.** For streaming terminal output, the obvious
Java approach is pty4j — JetBrains' pseudo-terminal library. It's the
right tool for creating a real PTY. But pty4j uses JNI and bundles native
.dylib files, which makes it incompatible with GraalVM native image
compilation. Since we want a native binary (fast startup, low memory,
feels like a system tool), pty4j is out. We'll use ProcessBuilder to
invoke tmux commands directly, piping stdin/stdout. tmux manages the PTY;
we just need to proxy the output. This is actually architecturally cleaner.

**Terminal adapters.** We initially designed the `open_in_terminal` MCP
tool assuming iTerm2 on macOS. But mid-design we realised: the Agent could
run on Linux too, and other terminals exist (WezTerm, Kitty). We made the
terminal integration pluggable — a `TerminalAdapter` interface with iTerm2
as the first implementation, auto-detection at startup, configuration via
`claudony.terminal=auto|iterm2|none`.

**The controller Claude.** This is the concept we're most excited about:
a Claude Code instance in its own iTerm2 window that has MCP tools giving
it full awareness of all other Claude sessions. It can create sessions,
list them, delete them, send input, read output, open them in iTerm2. The
controller becomes the interface for managing your Claude fleet, which
can itself use Claude to make decisions about that fleet.

## What Changed and Why

The biggest pivot was the iPad terminal assumption. We started expecting to
need a dedicated terminal app and ended up with a PWA. Once you see
"terminal emulator in a browser tab via xterm.js", the dedicated app idea
dissolves. The browser IS the app.

The "remote means iPad" assumption also fell. Any device with a browser is
now a valid client — other Macs, phones theoretically, anything. This also
surfaced the Mac Mini use case, which drove the Server/Agent split.

## What We Now Believe

The architecture is right. Two Quarkus modes, tmux as source of truth,
WebSocket for streaming, MCP via HTTP/SSE for the controller. The MCP
transport choice (HTTP/SSE rather than stdio) follows naturally from
Quarkus always being running — no need to spawn a separate process.

The xterm.js PWA approach solves the iPad problem more elegantly than any
native app would. There's no App Store, no installation, no SSH keys —
just a URL you bookmark or install to the home screen.

What we don't know: whether the keyboard experience on iPad will be good
enough for serious Claude Code work. We know xterm.js handles all the
right characters and key sequences. The iPad software keyboard is the
unknown — no Escape, no Ctrl combos natively. We're building a key bar
(a row of buttons above the terminal for common sequences), but we don't
know if it will feel natural until we try it.

We also don't know how well terminal history replay will work when
reconnecting — whether we can show previous output on reconnect without
artifacts.

---

**Next:** Implementing the Server core — REST API, WebSocket streaming,
session registry, tmux integration — and discovering that Java versions
and GraalVM are not where you expect them to be.
