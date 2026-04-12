---
layout: doc
title: Getting Started
section: Guide
description: Install and run Claudony — server, agent, and first session in minutes.
---

Claudony is a single binary with two modes: **server** and **agent**. The server runs on the machine that hosts your Claude Code sessions. The agent runs on your local machine and exposes an MCP endpoint for your controller Claude.

## Requirements

- macOS (Apple Silicon or Intel)
- [tmux](https://github.com/tmux/tmux) installed (`brew install tmux`)
- [Claude Code CLI](https://code.claude.com) authenticated

## Download

Grab the latest binary from [GitHub Releases](https://github.com/mdproctor/claudony/releases).

```bash
# Make it executable
chmod +x claudony
# Optional: move to PATH
mv claudony /usr/local/bin/claudony
```

## Start the Server

Run this on the machine that will host your sessions (Mac Mini, always-on MacBook, etc.):

```bash
claudony server --bind 0.0.0.0
```

On first run, the server prints an API key and opens the registration page at `http://localhost:7777/auth/register`. Register your passkey — this is how you log in from any browser.

**To persist sessions across server restarts**, set the encryption key env var so auth cookies survive:

```bash
QUARKUS_HTTP_AUTH_SESSION_ENCRYPTION_KEY=your-secret-32-chars \
  claudony server --bind 0.0.0.0
```

Default port is `7777`. Set `--port` to change it.

## Open the Dashboard

Open `http://your-server:7777/app/` in any browser. Log in with your passkey. You'll see the session dashboard — empty for now.

Install it as a PWA from your browser's menu for a full-screen native experience on iPad.

## Create Your First Session

From the dashboard, click **New Session**. Give it a name and a working directory. The server starts a new tmux pane and runs `claude` in it. You'll see the terminal appear in your browser.

## Start the Agent (optional)

The agent gives your controller Claude MCP tools to manage the colony. Run this on your local machine (the machine with iTerm2):

```bash
claudony agent --server http://your-server:7777
```

Then add the MCP endpoint to your controller Claude's config:

```json
{
  "mcpServers": {
    "claudony": {
      "url": "http://localhost:7778/mcp"
    }
  }
}
```

Your controller Claude can now `list_sessions`, `create_session`, `send_input`, `read_output`, and `open_in_terminal` for any session in the colony.

## Configuration

Key settings (can be passed as flags or env vars):

| Property | Default | Description |
|---|---|---|
| `remotecc.mode` | `server` | `server` or `agent` |
| `remotecc.port` | `7777` | HTTP port |
| `remotecc.bind` | `localhost` | Bind address (`0.0.0.0` for remote) |
| `remotecc.server.url` | `http://localhost:7777` | Agent → Server URL |
| `remotecc.default-working-dir` | `~/remotecc-workspace` | Default dir for new sessions |

## What's Next

- Read the [development diary](/claudony/blog/) to understand how it was built
- Open an issue on [GitHub](https://github.com/mdproctor) if something breaks
