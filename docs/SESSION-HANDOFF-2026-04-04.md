# Claudony — Session Handoff

**Date:** 2026-04-04 (late night)
**Status:** Plans 1, 2, 3 complete. System functional. Controller Claude not yet wired.

---

## Where We Are

Three implementation plans are done. The system runs. You can:

- Open `http://localhost:7777/app/` and see the dashboard
- Create, view, and delete Claude Code sessions
- Open a session and get a working terminal in the browser
- Navigate away and back — history is preserved on reconnect
- Install the app to iPad home screen as a PWA
- The MCP server at `http://localhost:7778/mcp` exposes 8 tools for a controller Claude

**64 tests passing.** Native binary compiles and starts in ~70ms.

---

## The One Thing Not Validated

The entire point of the system — a controller Claude instance managing other Claude sessions through conversation via MCP — has not been tested end-to-end. All the pieces exist:

- MCP server with 8 tools: list/create/delete/rename sessions, send input, get output, open in iTerm2, get server info
- Integration tests confirmed the MCP → REST → tmux chain works
- The Agent starts, detects iTerm2, checks clipboard

What hasn't happened: actually configuring a Claude Code instance to use `http://localhost:7778/mcp` as an MCP server and using it to manage sessions. That's the next session's first task.

---

## How to Start the System

```bash
# Terminal 1: Start Server
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev -Dclaudony.mode=server

# Terminal 2: Start Agent
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn quarkus:dev \
  -Dclaudony.mode=agent -Dquarkus.http.port=7778

# ⚠️ After any Java code change, restart both — hot-reload breaks WebSocket registration
```

Then open `http://localhost:7777/app/` in a browser.

---

## The Hot-Reload Problem

**This will bite you.** After every Java commit in dev mode, Quarkus hot-reloads. WebSocket connections after hot-reload return HTTP 101 but `@OnOpen` never fires. Symptoms:
- Dashboard works fine (REST endpoints fine)
- Terminal view connects but shows nothing, typing does nothing
- No WebSocket events in the log

Fix: kill and restart the server. Always after a Java commit. See `docs/BUGS-AND-ODDITIES.md` entry #1.

---

## Key Files to Know

| File | What it does |
|---|---|
| `CLAUDE.md` | Build commands, architecture, quirks — read this first |
| `docs/BUGS-AND-ODDITIES.md` | 15 documented bugs and quirks — check here before debugging |
| `docs/IDEAS.md` | Parked ideas with priority — check before adding features |
| `docs/superpowers/specs/2026-04-03-remotecc-design.md` | Full architecture design |
| `docs/project-blog/` | Five blog entries covering the full development journey |
| `docs/research/2026-04-03-existing-tools.md` | Why we built our own instead of using Webmux |

---

## The Architecture in One Paragraph

tmux is the source of truth. Quarkus runs in two modes (Server and Agent) from the same binary. The Server manages tmux sessions, streams terminal output over WebSocket via `pipe-pane` + named FIFOs (no PTY needed), and serves the web dashboard. The Agent exposes a JSON-RPC MCP endpoint for the controller Claude, proxies session commands to the Server via REST, and handles local automation (iTerm2 via AppleScript, tmux clipboard config). The frontend is vanilla HTML/JS/CSS with xterm.js from CDN — no build tool. Sessions survive any combination of tab closes, server restarts, and reconnects; only an explicit delete truly ends one.

---

## Terminal Streaming — Critical Implementation Detail

Do NOT attempt to use `tmux attach-session` via ProcessBuilder. It requires a real PTY. The browser will display "open terminal failed: not a terminal".

The correct approach (`TerminalWebSocket.java`):
1. Send capture-pane history synchronously FIRST (before any FIFO setup)
2. Create a named FIFO: `mkfifo /tmp/claudony-{connection-id}.pipe`
3. Start virtual thread opening the FIFO for reading (blocks until writer)
4. Start pipe-pane: `tmux pipe-pane -t {name} "cat > /tmp/claudony-{connection-id}.pipe"`
5. For input: `tmux send-keys -t {name} -l "{text}"` (the `-l` flag is critical)
6. On close: stop pipe-pane, delete FIFO

History replay details are in `docs/BUGS-AND-ODDITIES.md` entry #5.

---

## What the Next Session Should Do First

1. **Configure controller Claude with the MCP server** — add `http://localhost:7778/mcp` as an MCP server to a Claude Code instance, verify tools are visible, test `list_sessions` and `create_session` through conversation

2. **Validate end-to-end workflow** — controller Claude creates a session, opens it in iTerm2, user works in it, controller can read output and send input

3. **If MCP doesn't work** — check `docs/BUGS-AND-ODDITIES.md` entries #6 (Jackson) and the Agent startup logs. The most likely issue is the Agent not connecting to the Server.

4. **Authentication** — currently none. Needed before any Mac Mini deployment. This is in the open questions of the design doc.

---

## Test Commands

```bash
# Run everything
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test

# Specific suites
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=McpServerIntegrationTest
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=TerminalWebSocketTest

# Verify native binary still works after changes
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn package -Pnative -DskipTests
./target/claudony-1.0.0-SNAPSHOT-runner &
sleep 1 && curl http://localhost:7777/q/health && kill %1
```

---

## Git Log (most recent first)

```
280eca4 docs: add project blog — 5 entries covering full development journey
97d064f docs: add BUGS-AND-ODDITIES.md
7f38174 fix: restore one trailing space on last history line (cursor position)
23ffa4d fix: join history lines with \r\n between not after last (trailing blank line)
ed95a3a feat: use capture-pane -e for colored history replay
c0275d4 fix: remove all blank lines from history replay (pane grid artifacts)
8466cfb fix: send history before pipe-pane to eliminate race condition
99093b5 fix: replay pane scrollback on reconnect via capture-pane
199dc8f fix: replace tmux attach-session with pipe-pane+FIFO (PTY-free streaming)
b2273b6 fix: add quarkus-rest-client-reactive-jackson (Jackson for REST client)
[Plan 2: Agent + MCP]
[Plan 1: Server Core]
dca6ff3 feat: bootstrap Quarkus project
```

The terminal streaming history (199dc8f through 7f38174) represents the hardest part of the project — seven commits to get history replay right. See blog entry `2026-04-04-terminal-rendering-saga.md` for the full story.
