# Existing Tools Research
Date: 2026-04-03

Research conducted during initial brainstorming session. Captures what exists,
what we can reuse, and what we need to build ourselves.

---

## Webmux (windmill-labs/webmux)

**Repo:** https://github.com/windmill-labs/webmux
**Blog:** https://www.windmill.dev/blog/webmux
**License:** MIT
**Stack:** TypeScript + Bun + Python

### What It Is
Web dashboard for parallel AI coding agents (Claude, Codex). Combines
embedded terminals (xterm.js over WebSocket), GitHub PR/CI tracking,
service health monitoring, and Docker sandboxing.

### Why We're Not Using It Directly
- TypeScript/Bun — nothing imports into our Quarkus build
- Worktree-centric (git worktrees as primary unit) vs our session-centric design
- No iTerm2 integration
- No controller Claude / MCP architecture
- Management UI too simple for our Claude Code-specific vision

### What To Take From It (Reference, Not Code)

| Idea | How It Applies To Us |
|---|---|
| GitHub PR/CI integration pattern | Future enhancement — show PR status + CI badges per session |
| Docker sandbox approach | Future enhancement — run Claude sessions in isolated containers |
| Lifecycle hooks concept | Maps to our session create/delete events — run scripts on session lifecycle |
| WebSocket terminal streaming approach | Study the implementation (MIT licensed) to inform our Quarkus WebSocket handler |
| Auto-naming sessions via AI API | Could call Claude to suggest a session name based on the prompt/context |
| Service health monitoring | Show dev server health badges per session in our dashboard |

### Key Insight
Webmux proves the architecture works at scale (Windmill uses it in production
with many parallel agents). It validates: tmux + WebSocket + xterm.js as a
stack, and that a web dashboard is the right UX over raw terminal management.

---

## Other Tools Evaluated

### Muxplex (bkrabach/muxplex)
https://github.com/bkrabach/muxplex
- Lightweight web tmux dashboard, PWA support, session thumbnails
- Uses ttyd + xterm.js internally
- Python-based, MIT licensed
- Too simple for our needs, but confirms ttyd as a proven WebSocket-to-pty bridge

### conductor-mcp (GGPrompts/conductor-mcp)
https://github.com/GGPrompts/conductor-mcp
- MCP server for orchestrating Claude Code workers via tmux
- 33 tools for pane management, monitoring, and coordination
- No browser required — works in any terminal
- **USE AS REFERENCE:** Study its 33 tool definitions when designing our MCP tool set

### tmux-claude-mcp-server (michael-abdo)
https://github.com/michael-abdo/tmux-claude-mcp-server
- MCP server for hierarchical Claude instances via tmux
- 85% memory reduction vs traditional multi-server via bridge pattern
- Hierarchical naming (exec_1, mgr_1_1, spec_1_1_1)
- **USE AS REFERENCE:** Study its tool definitions and bridge pattern

### agent-deck (asheshgoplani)
https://github.com/asheshgoplani/agent-deck
- TUI (not web) session manager for AI coding agents
- Smart status detection: knows when Claude is thinking vs waiting vs idle
- MCP socket pooling (85-90% memory reduction)
- **USE AS REFERENCE:** Status detection logic for our session state indicators

### TabzChrome (GGPrompts/TabzChrome)
https://github.com/GGPrompts/TabzChrome
- Chrome extension approach (not relevant to our PWA direction)
- Architecture mirrors ours: Node.js backend → PTY + tmux → browser
- Has 85 MCP tools for browser control
- **USE AS REFERENCE:** MCP tool definitions

---

## Reusable Libraries (Confirmed for Our Build)

| Component | Library | Purpose |
|---|---|---|
| Terminal emulation (frontend) | xterm.js `@xterm/xterm` | Terminal rendering in browser |
| WebSocket attach | `@xterm/addon-attach` | Connect xterm.js to WebSocket |
| Terminal resize | `@xterm/addon-fit` | Handle terminal resize events |
| Search in terminal | `@xterm/addon-search` | Search through terminal output |
| Clipboard over WebSocket | `@xterm/addon-clipboard` + OSC 52 | Fixes macOS tmux clipboard issue |
| PTY management (JVM) | `pty4j` (JetBrains, MIT) | Java pseudo-terminal — Quarkus owns PTY directly, no ttyd needed |

---

## Libraries Evaluated But Not Used

| Library | Reason Not Used |
|---|---|
| ttyd | Replaced by pty4j — keeping PTY management inside Quarkus is cleaner |
| Webmux (code) | TypeScript, can't import into Quarkus |
| tmate | Read-only sharing, no management UI |
