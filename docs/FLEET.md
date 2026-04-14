# Claudony Fleet

Run Claudony on multiple machines and containers — one dashboard, all sessions.

---

## What Is the Fleet?

A Claudony fleet is a group of Claudony server instances that know about each other. Each instance shows sessions from every other instance in its dashboard. You can connect to any terminal from any dashboard.

```
MacBook dashboard ──► Local sessions
                 ──► Mac Mini sessions  (live)
                 ──► VPS sessions       (live)
                 ──► Docker sessions    (live)
```

There is no master instance. Every node is equal. The fleet is a peer mesh.

---

## Quick Start

### Two nodes with Docker Compose

1. **Generate a fleet key** on any Claudony instance:

   ```
   POST http://localhost:7777/api/peers/generate-fleet-key
   ```

   Copy the returned key — you'll use it on all instances.

2. **Create `docker-compose.yml`:**

   ```yaml
   services:
     claudony-a:
       image: claudony:latest
       ports: ["7777:7777"]
       volumes:
         - claudony-a-data:/root/.claudony
       environment:
         CLAUDONY_PEERS: "http://claudony-b:7777"
         CLAUDONY_FLEET_KEY: "your-fleet-key-here"
         CLAUDONY_SERVER_URL: "http://claudony-a:7777"

     claudony-b:
       image: claudony:latest
       ports: ["7778:7777"]
       volumes:
         - claudony-b-data:/root/.claudony
       environment:
         CLAUDONY_PEERS: "http://claudony-a:7777"
         CLAUDONY_FLEET_KEY: "your-fleet-key-here"
         CLAUDONY_SERVER_URL: "http://claudony-b:7777"

   volumes:
     claudony-a-data:
     claudony-b-data:
   ```

3. **Start:**

   ```bash
   docker compose up
   ```

Both instances appear in each other's dashboards immediately.

---

## Adding Instances Manually

From the dashboard **Fleet** panel, click **Add Peer**:
- **URL** — the address of the other Claudony instance
- **Name** — a friendly label (e.g. "Mac Mini", "VPS")
- **Terminal mode** — how to connect to that instance's terminals (see below)

Or via REST:

```bash
curl -X POST http://localhost:7777/api/peers \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: your-api-key" \
  -d '{"url": "http://mac-mini:7777", "name": "Mac Mini", "terminalMode": "direct"}'
```

When you add one peer, Claudony exchanges peer lists automatically — add one seed and the mesh fills in.

---

## LAN Auto-Discovery

Enable mDNS auto-discovery to find Claudony instances on your local network automatically — no manual configuration needed:

```properties
claudony.mdns-discovery=true
```

Or via environment variable: `CLAUDONY_MDNS_DISCOVERY=true`

Auto-discovered instances appear in the fleet panel with a `mdns` badge. They are not persisted — they re-appear after restart if still on the network. To make a discovered peer permanent, click **Save** in the fleet panel.

mDNS works on home/office LANs. It does not work across the internet or on most VPN/Docker overlay networks.

---

## Configuration

```properties
# Comma-separated peer URLs — always known at startup, cannot be removed via API
claudony.peers=http://mac-mini:7777,http://vps.example.com:7777

# Shared secret — all fleet members must have the same value
# Generate with POST /api/peers/generate-fleet-key
claudony.fleet-key=

# Enable mDNS auto-discovery on LAN (default: false)
claudony.mdns-discovery=false
```

All of the above can be set via environment variables:

```bash
CLAUDONY_PEERS=http://mac-mini:7777
CLAUDONY_FLEET_KEY=your-secret
CLAUDONY_MDNS_DISCOVERY=true
```

---

## Terminal Modes

Each peer has a **terminal mode** that controls how your browser connects to sessions on that peer.

### `DIRECT` (default)

Your browser connects directly to the remote Claudony instance. The remote instance must be reachable from your browser's network (LAN access, public IP, or VPN).

Best for: instances on the same LAN, VPS with a public address.

### `PROXY`

Your local Claudony bridges the WebSocket connection. The remote instance only needs to be reachable from your local Claudony server — your browser doesn't connect to it directly.

Best for: Docker containers on an internal network, instances behind NAT, SSH-tunnelled instances.

Change terminal mode:

```bash
curl -X PATCH http://localhost:7777/api/peers/{id} \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: your-api-key" \
  -d '{"terminalMode": "proxy"}'
```

---

## Fleet Key

The fleet key authenticates peer-to-peer calls. All instances in a fleet must share the same fleet key.

**Generate:**

```bash
curl -X POST http://localhost:7777/api/peers/generate-fleet-key \
  -H "X-Api-Key: your-api-key"
```

The key is saved to `~/.claudony/fleet-key` and returned once in the response. Copy it to all other instances via `CLAUDONY_FLEET_KEY` environment variable or `claudony.fleet-key` config property.

**Without a fleet key:** peers will reject each other's API calls with 401. A warning is logged at startup.

---

## Fleet API Reference

```
GET    /api/peers                    — list all peers with health and circuit state
POST   /api/peers                    — add a peer manually
DELETE /api/peers/{id}               — remove a peer (config-file peers cannot be removed)
PATCH  /api/peers/{id}               — update name or terminal mode
GET    /api/peers/{id}/sessions      — sessions from one specific peer
POST   /api/peers/{id}/ping          — force immediate health check
POST   /api/peers/generate-fleet-key — generate and save a new fleet key
```

---

## Dashboard Fleet Panel

The **Fleet** panel in the dashboard sidebar shows:

- All known peers with a health indicator (● green = up, ● amber = stale, ● red = down)
- Circuit breaker state per peer
- How each peer was discovered (config / manual / mdns)
- Last seen timestamp

Session cards show an **instance badge** — the name of the Claudony instance the session runs on. Stale sessions (from a peer that is currently unreachable) show a clock icon and "last seen N minutes ago".

---

## Robustness

**A peer going offline never breaks your local instance.** Claudony handles network instability as follows:

| Situation | Behaviour |
|---|---|
| Peer unreachable | Marked `down`; cached sessions shown as stale |
| 3 consecutive failures | Circuit opens; peer skipped for backoff period |
| Peer recovers | Health check detects it; circuit resets to normal |
| Slow peer | 2s timeout per peer; other peers unaffected |
| mDNS unavailable | Warning logged; static/manual discovery continue |
| `peers.json` corrupted | Warning logged; manual peers start empty; static config still applies |
| Network partition | Each side shows what it can reach; heals automatically when connectivity returns |

Backoff schedule after circuit opens: 30s → 1m → 2m → 5m (capped).

---

## Deployment Topologies

### Home Network (MacBook + Mac Mini)

```bash
# Mac Mini — add your MacBook as a peer
curl -X POST http://mac-mini:7777/api/peers \
  -d '{"url": "http://macbook.local:7777", "name": "MacBook", "terminalMode": "direct"}'

# MacBook — add the Mac Mini
curl -X POST http://localhost:7777/api/peers \
  -d '{"url": "http://mac-mini.local:7777", "name": "Mac Mini", "terminalMode": "direct"}'
```

Or use mDNS and they'll find each other automatically.

### Docker Compose (isolated containers)

Use `terminalMode: proxy` if containers are on an internal Docker network not reachable from your browser directly. Use `terminalMode: direct` if you expose each container's port.

### VPS (public internet)

Add via static config or manual registration. Use the public IP/hostname. Ensure port 7777 is open. `terminalMode: direct` works if the browser can reach the VPS; `terminalMode: proxy` routes traffic through whichever instance your browser is connected to.

---

## Security Notes

- The fleet key is a shared secret — keep it out of version control
- Use environment variables or a secrets manager to distribute it
- Peer-to-peer calls are authenticated but not encrypted by default — run behind HTTPS/TLS for production
- Per-user node visibility and access profiles are planned for a future release
