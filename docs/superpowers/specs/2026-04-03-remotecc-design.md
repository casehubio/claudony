# RemoteCC — Design Snapshot
**Date:** 2026-04-03
**Status:** Approved — ready for implementation planning
**Supersedes:** *(none — first snapshot)*

---

## What We're Building

A system that lets you run Claude Code sessions on one machine (MacBook or
headless Mac Mini) and access them from any device (iPad primary, other Macs)
via a browser — with no special software required on the remote device. A
"controller" Claude instance manages all sessions via MCP tools. Sessions
persist independently of any window or tab being open; only an explicit
controller action truly ends a session.

---

## How We Got Here — Key Decisions

### 1. WebSocket over SSH for remote access
**Chosen:** Browser + WebSocket only. SSH dropped from initial scope.
**Why:** Simpler client setup — any browser works, no SSH keys or terminal
app needed on the remote device.
**Rejected:** SSH+tmux (requires terminal app + key setup per remote device).
SSH can be added later if a need arises.

### 2. tmux as single source of truth for sessions
**Chosen:** Every Claude session is a tmux session. All other access
(iTerm2, browser) are views into that session.
**Why:** Sessions survive closing any window or browser tab. Only an explicit
controller action truly ends a session. Multiple clients can attach
simultaneously — they mirror each other automatically.
**Rejected:** iTerm2-only (sessions die with the window), pure headless
process (no local terminal access).

### 3. Hybrid local access — iTerm2 optional, not required
**Chosen:** Sessions run headlessly in tmux by default. iTerm2 windows can
optionally be opened via `tmux -CC`, attaching to the same session.
**Why:** Flexibility — work locally in iTerm2 or purely through the browser.
Closing an iTerm2 window never kills the session.
**Trade-off:** Some iTerm2 features don't work through tmux (imgcat, shell
integration, triggers). Mitigated by `tmux -CC` integration mode which
preserves ~95% of native iTerm2 experience. Clipboard fixed via config.

### 4. Quarkus native as the server framework
**Chosen:** Single Quarkus binary (compiled to native image via GraalVM)
handles all server concerns.
**Why:** User has deep Quarkus expertise, fast startup, low memory, strong
reactive WebSocket support via Vert.x, native image feels like a system tool.
**Rejected:** Node.js + Express (less familiar), Python + ttyd (extra
dependency, looser integration).

### 5. ProcessBuilder over pty4j for PTY management
**Chosen:** Quarkus uses `ProcessBuilder` to spawn `tmux attach-session -t
<name>` and pipes stdin/stdout over the WebSocket. No Java PTY library needed.
**Why:** pty4j (JetBrains) uses JNI and bundled native libraries — incompatible
with GraalVM native image. Since tmux manages the PTY, Quarkus only needs
to pipe to it. Simpler, native-image safe, and architecturally correct since
tmux is already the session source of truth.
**Rejected:** pty4j (native image incompatible), ttyd subprocess (extra
binary, less integrated).

### 6. MCP via HTTP/SSE transport, on Agent only
**Chosen:** The Agent (not the Server) exposes the MCP endpoint over HTTP/SSE.
The Server exposes REST only.
**Why:** Quarkus is always running — no need to spawn a stdio process. HTTP/SSE
is the right fit. MCP lives on the Agent because the Agent handles local
automation (iTerm2, clipboard) which cannot run on a remote server.
**Rejected:** stdio MCP (requires process spawning, doesn't suit persistent
server), MCP on Server (can't do local AppleScript from remote machine).

### 7. Two-service architecture: Server + Agent
**Chosen:** Same Quarkus binary, two modes configured via properties.
Server manages sessions. Agent is a lightweight local proxy with MCP.
**Why:** Clean separation — the Server is location-agnostic (runs on Mac Mini
or MacBook), the Agent is always local (runs on the machine with iTerm2).
Even in a MacBook-only setup, two instances run locally. Retrofitting this
split later would be hard; designed in from the start.
**Rejected:** Single service (can't separate local automation from remote
session management), separate binaries (unnecessary complexity).

### 8. Frontend: xterm.js SPA with PWA support
**Chosen:** Server serves a SPA using xterm.js for terminal emulation.
PWA manifest enables install-to-home-screen on iPad.
**Why:** No dedicated iPad app needed. Any browser works. iPad keyboard gap
addressed with a custom key bar above the terminal.
**Rejected:** Native iPad app (App Store friction), Blink Shell (requires
SSH setup), existing tools like Webmux (too simple, not Claude-centric).

### 9. Clipboard auto-detection and fix on startup
**Chosen:** Agent checks tmux clipboard config on startup and offers to
auto-fix (`set -g set-clipboard on` for tmux 3.2+).
**Why:** Clipboard is the most impactful loss when using tmux. Should be
invisible to the user — detect and fix, don't document and forget.

---

## Architecture

### Two Quarkus Instances (same binary, different mode)

```
┌──────────────────────────────────────────────────────────────┐
│  REMOTECC SERVER  (Mac Mini OR MacBook)                      │
│                                                              │
│  REST API     /api/sessions  — session CRUD                  │
│  WebSocket    /ws/{id}       — terminal streaming            │
│  Static       /app           — web dashboard (xterm.js)      │
│                                                              │
│  Owns: tmux sessions, session registry, terminal streaming   │
│  NO MCP — exposes REST only                                  │
│  Binds: 0.0.0.0 (Mac Mini) or localhost (MacBook-only)      │
└──────────────────────────────┬───────────────────────────────┘
                               │ REST + WebSocket
              ┌────────────────┴─────────────────┐
              │                                  │
┌─────────────▼────────────────┐    ┌────────────▼────────────┐
│  REMOTECC AGENT  (MacBook)   │    │  Browser / PWA / iPad   │
│                              │    │                         │
│  MCP  /mcp  — HTTP/SSE       │    │  Connects directly to   │
│                              │    │  Server WebSocket for   │
│  Proxies to Server REST API  │    │  terminal sessions      │
│  Local iTerm2 AppleScript    │    │                         │
│  Local clipboard detection   │    │  Uses Server /app for   │
│                              │    │  management dashboard   │
│  Binds: localhost only       │    │                         │
│  NO web UI, NO WebSocket     │    └─────────────────────────┘
│  NO tmux management          │
└──────────────┬───────────────┘
               │ MCP HTTP/SSE
┌──────────────▼───────────────┐
│  Controller Claude           │
│  (iTerm2 on MacBook)         │
└──────────────────────────────┘
```

### Responsibility Split

| Concern | Server | Agent |
|---|---|---|
| tmux session lifecycle | ✓ | proxies |
| Terminal streaming (WebSocket) | ✓ | — |
| Web dashboard + SPA | ✓ | — |
| Session registry (in-memory + tmux) | ✓ | reads from server |
| MCP endpoint | — | ✓ |
| Local terminal integration | — | ✓ (adapter-based, macOS + Linux) |
| Clipboard detection + fix | — | ✓ (local only) |

### Deployment Configurations

**MacBook-only:**
```properties
# Instance 1 — Server (port 7777)
remotecc.mode=server
remotecc.bind=localhost

# Instance 2 — Agent (port 7778)
remotecc.mode=agent
remotecc.server.url=http://localhost:7777
```

**Mac Mini server + MacBook client:**
```properties
# On Mac Mini — Server (port 7777)
remotecc.mode=server
remotecc.bind=0.0.0.0

# On MacBook — Agent (port 7778)
remotecc.mode=agent
remotecc.server.url=http://mac-mini:7777
```

---

## Session Lifecycle

```
CREATE
  Controller Claude → Agent MCP tool: create_session(name, working_dir, command?)
  Agent → Server REST: POST /api/sessions
  Server → tmux new-session -d -s <name> -c <working_dir>
  Server → runs `claude` inside session
  Agent → optionally: AppleScript opens iTerm2 window (tmux -CC attach)
  Returns: session id, WebSocket URL, browser URL

CONNECT (browser tab)
  Browser opens: ws://<server>/ws/<session-id>
  Server WebSocket handler → ProcessBuilder: tmux attach-session -t <name>
  Pipes subprocess stdin/stdout ↔ WebSocket
  Terminal resize → WebSocket message → SIGWINCH to process

DISCONNECT (tab closes)
  WebSocket closes → subprocess detaches from tmux
  tmux session keeps running, Claude keeps running

DELETE
  Controller Claude → Agent MCP: delete_session(id)
  OR user clicks delete in dashboard
  Agent → Server REST: DELETE /api/sessions/<id>
  Server → tmux kill-session -t <name>
  Server → closes any open WebSocket connections

SESSION REGISTRY
  In-memory on Server, bootstrapped from: tmux list-sessions on startup
  Survives Quarkus Server restart
```

---

## Frontend (Web Dashboard + Terminal)

### Management Dashboard (`/app`)
- Session cards: name, status (active/waiting/idle), working dir, last active
- Create session button → name + working dir picker
- Delete session (with confirmation)
- Click card → opens session terminal in new browser tab
- Responsive — works on iPad, MacBook browser

### Session Terminal (`/app/session/<id>`)
- xterm.js fills the screen
- iPad key bar (always visible on touch, hideable on desktop):
  `Esc  Ctrl+C  Ctrl+D  Tab  ⇥  \`  |  ~  ↑  ↓`
- Status indicator: Claude thinking / waiting for input / shell idle
- Auto-reconnect on WebSocket drop — reattaches silently to same tmux session
- Back button → management dashboard (session keeps running)

### Libraries
| Library | Purpose |
|---|---|
| `@xterm/xterm` | Terminal emulation |
| `@xterm/addon-attach` | WebSocket connection |
| `@xterm/addon-fit` | Terminal resize handling |
| `@xterm/addon-search` | Search through terminal output |
| `@xterm/addon-clipboard` | OSC 52 clipboard over WebSocket |

---

## MCP Tools (Agent)

| Tool | Parameters | Description |
|---|---|---|
| `list_sessions` | — | All sessions: id, name, status, working_dir, last_active, browser_url |
| `create_session` | `name`, `working_dir`, `command?` | Create tmux session + start claude |
| `delete_session` | `session_id` | Kill tmux session |
| `rename_session` | `session_id`, `new_name` | Rename the session |
| `send_input` | `session_id`, `text` | Send keystrokes to session |
| `get_output` | `session_id`, `lines?` | Fetch recent terminal output |
| `open_in_terminal` | `session_id` | Open a local terminal window for the session — adapter-based, see Terminal Adapters |
| `get_server_info` | — | Server mode, version, clipboard status, network address |

---

## Terminal Adapters (Agent)

The Agent's `open_in_terminal` MCP tool delegates to a pluggable adapter.
The adapter is configured via `remotecc.terminal` or auto-detected.

| Adapter | Platform | Mechanism | Status |
|---|---|---|---|
| `iterm2` | macOS | AppleScript + `tmux -CC attach` | First implementation |
| `auto` | macOS/Linux | Detects best available adapter | Default |
| *(others)* | macOS/Linux | e.g. WezTerm, Kitty, GNOME Terminal | Future |

**Auto-detection order (macOS):** iTerm2 → Terminal.app → none (tool disabled)
**Auto-detection order (Linux):** WezTerm → Kitty → GNOME Terminal → none

Each adapter implements a simple interface:
- `isAvailable()` — can this adapter run on the current machine?
- `openSession(sessionName, serverUrl)` — open a terminal window attached to the tmux session

If no adapter is available (e.g. headless server running Agent), `open_in_terminal`
returns a clear error rather than silently failing.

---

## Startup Sequence

### Server startup
1. Check tmux installed + version ≥ 3.2
2. Bootstrap session registry from `tmux list-sessions`
3. Detect bind mode (localhost vs 0.0.0.0) from config
4. Start serving — log URL

### Agent startup
1. Verify Server is reachable at configured URL
2. Detect platform (macOS/Linux) + run terminal adapter auto-detection → enables/disables `open_in_terminal` tool
3. Check tmux clipboard config → offer to auto-fix if missing
4. Detect local vs remote server → surface in `get_server_info`
5. Start MCP endpoint — log ready

### Configuration
```properties
remotecc.mode=server|agent
remotecc.port=7777
remotecc.bind=localhost
remotecc.server.url=http://localhost:7777   # agent only
remotecc.claude-command=claude
remotecc.tmux-prefix=remotecc-
remotecc.terminal=auto          # auto | iterm2 | wezterm | kitty | none
```

---

## Open Questions (Still To Design)

- **Authentication / access control** — important when Server is on Mac Mini
  and accessible over the network. Currently undesigned. Must be addressed
  before any public or multi-user deployment.
- **Frontend framework choice** — plain web components vs lightweight JS
  framework for the SPA. To be decided during implementation.
- **iTerm2 on MacBook connecting to Mac Mini sessions** — the Agent's
  `open_in_iterm2` tool will SSH to the Mac Mini to attach. AppleScript
  command TBD.
- **Session status detection** — distinguishing Claude thinking / waiting /
  idle by inspecting tmux output. Logic TBD.
- **WebSocket reconnect protocol** — heartbeat interval, reconnect backoff,
  how the client knows which session to reattach to after a drop.

---

## Libraries Used

| Component | Library | License |
|---|---|---|
| Terminal emulation | xterm.js + addons | MIT |
| Terminal bridging | ProcessBuilder → tmux (no PTY lib needed) | — |
| Server framework | Quarkus (native image via GraalVM) | Apache 2.0 |
| Reactive WebSocket | Vert.x (via Quarkus) | Apache 2.0 |

---

## Related Documents

- Research: [docs/research/2026-04-03-existing-tools.md](../../research/2026-04-03-existing-tools.md)
- Ideas log: [docs/ideas/IDEAS.md](../../ideas/IDEAS.md)
- ADRs: `docs/adr/` *(none created yet — to be added during implementation)*
