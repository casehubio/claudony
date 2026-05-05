# Design: Fleet Manager — Multi-Instance Claudony Mesh

**Date:** 2026-04-14
**Status:** Approved
**User-facing doc:** `docs/FLEET.md`

---

## Problem

Claudony currently runs as a single isolated server. There is no way to manage or observe sessions on other machines (Mac Mini, VPS, Docker containers) from one dashboard. Deploying a new instance requires manual setup with no standardised tooling.

---

## Goals

- Every Claudony instance knows about every other instance in its fleet
- One dashboard shows and controls sessions across all instances
- Deployment to Docker containers is simple and standardised
- The system degrades gracefully when peers are unreachable — local sessions always work
- Basic peer-to-peer authentication via a shared fleet key
- All topology configuration is settable via REST API (and thus the dashboard UI)

## Non-Goals (explicitly deferred)

- User profiles / per-user node visibility (ACL layer — future)
- Role-based access control
- Automated fleet key rotation
- Native image Docker support (JVM mode first)

---

## Architecture Overview

Every Claudony server instance participates as both a **peer server** and a **peer client**. No master/worker distinction — the mesh is fully symmetric.

```
┌─────────────────────────────────────────────────────────────────┐
│  Instance A (MacBook)                                           │
│                                                                 │
│  StaticConfigDiscovery ─┐                                       │
│  ManualRegistrationDiscovery ─┼──► PeerRegistry ──► PeerClient │
│  MdnsDiscovery ─────────┘         (circuit breaker,            │
│                                    health loop,                 │
│                                    session cache)               │
│                                                                 │
│  /api/peers  ◄──────── REST ────────────────────────────────── │
│  /api/sessions (federated) ◄─── fan-out to all healthy peers   │
│  /ws/proxy/{peerId}/{sessionId} ◄─── WebSocket bridge          │
└─────────────────────────────────────────────────────────────────┘
         │                              │
    X-Api-Key (fleet-key)         X-Api-Key (fleet-key)
         │                              │
┌────────▼──────────────┐   ┌──────────▼────────────┐
│  Instance B (Mac Mini) │   │  Instance C (Docker)  │
│  /api/sessions         │   │  /api/sessions        │
│  /ws/{sessionId}       │   │  /ws/{sessionId}      │
└────────────────────────┘   └───────────────────────┘
```

---

## Peer Data Model

```json
{
  "id": "uuid",
  "url": "http://mac-mini:7777",
  "name": "Mac Mini",
  "source": "config | manual | mdns",
  "terminalMode": "direct | proxy",
  "health": "up | down | unknown",
  "circuitState": "closed | open | half-open",
  "lastSeen": "2026-04-14T18:30:00Z",
  "sessionCount": 3
}
```

Session records gain two new fields:

```json
{
  "...existing fields...",
  "instanceUrl": "http://mac-mini:7777",
  "instanceName": "Mac Mini",
  "stale": false
}
```

Local sessions have `instanceUrl` set to the local server's own URL (from `claudony.server.url`). The dashboard uses this field to badge every session card regardless of origin.

---

## New Config Properties

```properties
# Comma-separated list of peer URLs — loaded by StaticConfigDiscovery at startup
claudony.peers=http://mac-mini:7777,http://vps.example.com:7777

# Shared secret for peer-to-peer API calls — same X-Api-Key mechanism as Agent→Server
# Generate with POST /api/peers/generate-fleet-key
claudony.fleet-key=

# Enable mDNS auto-discovery on LAN (disabled by default)
claudony.mdns-discovery=false
```

---

## New Java Components

### `PeerRegistry` (`@ApplicationScoped`)

The single authoritative peer list. Responsibilities:
- Holds all known peers regardless of discovery source
- Deduplicates by URL; source priority: `config` > `manual` > `mdns`
- Runs a background virtual-thread health check loop (30s interval)
- Maintains per-peer circuit breaker state
- Caches last successful session list per peer
- Persists `manual` and `mdns` peers to `~/.claudony/peers.json` (atomic write)
- Loads `peers.json` on startup; logs WARN and continues if corrupted

### `PeerClient` (Quarkus REST client)

Typed REST client for calling peer APIs. Used by:
- Health check loop: `GET {peerUrl}/q/health`
- Session federation: `GET {peerUrl}/api/sessions`
- Peer list exchange: `GET {peerUrl}/api/peers`

Attaches `X-Api-Key: {claudony.fleet-key}` on every call.

Timeouts (hard limits, not configurable):
- Health check: 5s
- Session federation: 2s
- Peer list exchange: 3s

### `PeerDiscoverySource` (interface)

```java
public interface PeerDiscoverySource {
    String name();
    List<PeerCandidate> discover();  // called at startup + periodically
}
```

Three implementations:
- `StaticConfigDiscovery` — reads `claudony.peers` once at startup
- `ManualRegistrationDiscovery` — triggered by REST API writes
- `MdnsDiscovery` — advertises and listens for `_claudony._tcp.local.`

### `PeerResource` (new REST resource)

```
GET    /api/peers                    — list all peers
POST   /api/peers                    — add peer {url, name, terminalMode}
DELETE /api/peers/{id}               — remove (config peers → 405 Method Not Allowed)
PATCH  /api/peers/{id}               — update name or terminalMode
GET    /api/peers/{id}/sessions      — sessions from one specific peer
POST   /api/peers/{id}/ping          — force immediate health check
POST   /api/peers/generate-fleet-key — generate + save fleet key, return once
```

### `SessionResource` (modified)

`listSessions()` becomes a federated call:
1. Collect local sessions (always present, never blocked)
2. Fan out in parallel to all `CLOSED`-circuit peers with 2s timeout
3. Merge results; stale cached sessions tagged `stale: true` + `lastSeen`
4. Return combined list

### `ProxyWebSocket` (new endpoint)

Path: `/ws/proxy/{peerId}/{sessionId}`

On browser connect:
1. Look up peer in `PeerRegistry`
2. Open WebSocket to `ws://{peerUrl}/ws/{sessionId}` with fleet key header
3. Pipe frames bidirectionally (Vert.x `WebSocketBase.handler()`)
4. On upstream connect failure: close browser WS with code 1014, message "peer unreachable"
5. On upstream disconnect: close browser WS cleanly

---

## Discovery Mechanisms

### `StaticConfigDiscovery`

Reads `claudony.peers` at startup. Peers appear immediately with `source=config`. Cannot be removed via API — always present while in config. Use for Docker Compose (service names resolve via Docker DNS) and known-IP deployments.

### `ManualRegistrationDiscovery`

Peers added via `POST /api/peers`. Persisted to `~/.claudony/peers.json`. On first successful contact with a new peer, exchange peer lists — add one seed and the mesh fills in:

```
A knows B (manually added)
A contacts B, B returns its peer list {C, D}
A adds C and D to registry
```

Exchange is idempotent. Re-connecting after a partition never causes duplicates.

### `MdnsDiscovery`

Enabled via `claudony.mdns-discovery=true`. Uses Vert.x service discovery mDNS support. Each instance advertises `_claudony._tcp.local.` with TXT records: `{name, version, fleetKeyHash}`. Discovered peers appear with `source=mdns`, in-memory only (not persisted — re-discovered after restart). Can be promoted to `manual` (persisted) via dashboard.

Fails non-fatally: if mDNS is unavailable (VPN, Docker non-multicast network), logs WARN at startup and stops — other discovery sources unaffected.

---

## Resilience

### Circuit Breaker (per peer)

```
CLOSED ──(3 consecutive failures)──► OPEN ──(backoff expires)──► HALF-OPEN
  ▲                                                                    │
  └──────────────(health check succeeds)──────────────────────────────┘
                                             │
                             (health check fails)──► OPEN (exponential backoff)
```

Backoff schedule: 30s → 1m → 2m → 5m (capped at 5m).

- `CLOSED`: normal operation, calls proceed
- `OPEN`: peer skipped entirely; cached sessions shown as stale
- `HALF-OPEN`: one test health check attempted; success → CLOSED, failure → OPEN

### Session Cache

`PeerRegistry` stores the last successful `List<Session>` from each peer. When a peer is `OPEN`, cached sessions are returned with `stale: true` and the `lastSeen` timestamp. The dashboard renders these with a visual indicator. No cached data is ever older than the last successful contact.

### Parallel Fan-Out

Session federation never waits for one peer before calling another. All peer calls are fired simultaneously (virtual threads). Each has its own 2s timeout. A single slow or dead peer does not delay the response.

### Persistence Safety

`peers.json` is written atomically:
1. Write to `peers.json.tmp`
2. Rename to `peers.json` (atomic on POSIX)

On startup, if `peers.json` is missing or corrupted: log `WARN`, continue with empty manual peer list. Static config peers are always applied regardless.

### mDNS Non-Fatal

If mDNS fails to initialise (no multicast, wrong network type), log `WARN` and continue. Static and manual discovery unaffected.

### Split-Brain

No resolution attempted. Each side shows what it can reach. When partition heals, health checks detect recovery and circuit breakers reset. This is not a consensus system.

---

## Fleet Key Authentication

`claudony.fleet-key` — a shared secret distributed to all fleet members. Sent as `X-Api-Key` header on all peer-to-peer REST and WebSocket calls.

`ApiKeyAuthMechanism` (existing) extended to accept fleet key alongside the existing agent API key. Same header, same mechanism, one additional valid value.

**Generation:** `POST /api/peers/generate-fleet-key` — generates a 32-byte SecureRandom key (base64url, same as `EncryptionKeyConfigSource`), saves to `~/.claudony/fleet-key`, returns it once in the response body. Operator copies it to all other instances via their config or environment variable.

**No fleet key configured:** cross-peer calls will fail with 401 on the receiving end. Startup logs `WARN: claudony.fleet-key not set — peer-to-peer calls will be rejected by authenticated peers`.

---

## Terminal Connectivity

Per-peer `terminalMode` field, set via `PATCH /api/peers/{id}` or dashboard. Default: `DIRECT`.

### `DIRECT`

Dashboard detects `instanceUrl` on a session. "Connect" navigates browser to `{instanceUrl}/app/session/{id}`. Remote Claudony handles auth and WebSocket. Requires browser to have direct network access to the remote instance.

### `PROXY`

Browser connects to `ws://local:7777/ws/proxy/{peerId}/{sessionId}`. Local Claudony opens upstream WebSocket to remote and pipes bytes. Browser never needs to reach remote directly. Required for Docker internal networks or instances behind NAT.

---

## Docker Deployment

### `Dockerfile` (JVM mode)

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/quarkus-app/ .
EXPOSE 7777
ENTRYPOINT ["java", \
  "-Dclaudony.mode=server", \
  "-Dclaudony.bind=0.0.0.0", \
  "-jar", "quarkus-run.jar"]
```

### `docker-compose.yml` (two-node example)

```yaml
services:
  claudony-a:
    build: .
    ports: ["7777:7777"]
    volumes:
      - claudony-a-data:/root/.claudony
    environment:
      CLAUDONY_PEERS: "http://claudony-b:7777"
      CLAUDONY_FLEET_KEY: "${CLAUDONY_FLEET_KEY}"
      CLAUDONY_SERVER_URL: "http://claudony-a:7777"

  claudony-b:
    build: .
    ports: ["7778:7777"]
    volumes:
      - claudony-b-data:/root/.claudony
    environment:
      CLAUDONY_PEERS: "http://claudony-a:7777"
      CLAUDONY_FLEET_KEY: "${CLAUDONY_FLEET_KEY}"
      CLAUDONY_SERVER_URL: "http://claudony-b:7777"

volumes:
  claudony-a-data:
  claudony-b-data:
```

`~/.claudony/` is a named volume so the encryption key, fleet key, and peer list survive container restarts.

---

## Dashboard Changes

- **Session cards** — instance badge (colour-coded by node: `Mac Mini`, `VPS`, `Local`); stale sessions show clock icon + "last seen N min ago"
- **Fleet panel** (sidebar) — lists all peers with health indicator (●green / ●amber / ●red), circuit state, source tag, last seen timestamp
- **Add Peer** button → modal: URL, name, terminal mode selector
- **Peer actions**: ping, remove, change terminal mode — all via REST
- **Session connect** — DIRECT: opens `{instanceUrl}/app/session/{id}` in same tab; PROXY: connects via local proxy WebSocket, transparent to user

---

## Files Changed

| File | Action |
|---|---|
| `src/main/java/dev/claudony/server/fleet/PeerRegistry.java` | **Create** |
| `src/main/java/dev/claudony/server/fleet/PeerClient.java` | **Create** |
| `src/main/java/dev/claudony/server/fleet/PeerResource.java` | **Create** |
| `src/main/java/dev/claudony/server/fleet/PeerDiscoverySource.java` | **Create** (interface) |
| `src/main/java/dev/claudony/server/fleet/StaticConfigDiscovery.java` | **Create** |
| `src/main/java/dev/claudony/server/fleet/ManualRegistrationDiscovery.java` | **Create** |
| `src/main/java/dev/claudony/server/fleet/MdnsDiscovery.java` | **Create** |
| `src/main/java/dev/claudony/server/fleet/ProxyWebSocket.java` | **Create** |
| `src/main/java/dev/claudony/server/SessionResource.java` | **Modify** — federated listSessions() |
| `src/main/java/dev/claudony/server/auth/ApiKeyAuthMechanism.java` | **Modify** — accept fleet key |
| `src/main/java/dev/claudony/config/ClaudonyConfig.java` | **Modify** — add fleet-key, peers, mdns-discovery |
| `src/main/resources/application.properties` | **Modify** — new properties |
| `src/main/resources/META-INF/resources/app/dashboard.js` | **Modify** — fleet panel, instance badges |
| `src/main/resources/META-INF/resources/app/style.css` | **Modify** — instance badge styles |
| `Dockerfile` | **Create** |
| `docker-compose.yml` | **Create** |
| `docs/FLEET.md` | **Create** — user-facing fleet documentation |
| `docs/DESIGN.md` | **Modify** — link to FLEET.md |
| `CLAUDE.md` | **Modify** — note fleet package, test count |

---

## Testing

### Unit tests (plain JUnit, no Quarkus)
- `PeerRegistryTest` — circuit breaker state transitions, deduplication by URL, source priority, atomic peers.json write/read, corrupted-file recovery
- `StaticConfigDiscoveryTest` — parses comma-separated URLs, handles blanks/duplicates
- `MdnsDiscoveryTest` — graceful failure when mDNS unavailable

### Integration tests (`@QuarkusTest`)
- `PeerResourceTest` — CRUD endpoints, 405 on config peer delete, generate-fleet-key
- `SessionFederationTest` — federated `/api/sessions` with mocked peer (returns stale when peer down)
- `ProxyWebSocketTest` — proxy WebSocket connects and pipes frames, handles upstream failure
- `FleetKeyAuthTest` — peer calls accepted with fleet key, rejected without

### Docker E2E (manual)
- Two-container compose: sessions from both visible in dashboard
- Kill one container: remaining shows cached/stale sessions for downed peer
- Restart container: health check recovers, live sessions return

---

## Sub-Project Build Order

This feature ships in two phases, each independently valuable:

**Phase 1 — Fleet infrastructure**
`PeerRegistry`, `PeerClient`, `PeerResource`, three discovery sources, fleet key auth, federated `/api/sessions`, `Dockerfile`, `docker-compose.yml`, `docs/FLEET.md`

REST API is complete and testable before any dashboard changes. Operators can manage the fleet via API immediately.

**Phase 2 — Fleet UI + terminal proxy**
Dashboard fleet panel, session instance badges, stale indicators, PROXY WebSocket bridge

Phase 2 builds on Phase 1 data model without changes to the REST API.
