# Mesh Observation Panel — Design Spec

**Goal:** Add a collapsible right panel to the Claudony dashboard that observes the
local Qhorus agent communication mesh — showing active channels, online instances,
and message activity in real time.

**Context:** Qhorus is embedded in Claudony (Phase 8). Agents connecting to the
unified `/mcp` endpoint can register, create channels, and exchange messages. The
dashboard currently has no way to observe any of this. This panel closes that loop.

**Scope:** Local Qhorus instance only. No cross-fleet aggregation. No human
interjection (future). Read-only observation.

---

## Layout

Option A: right panel alongside the existing Fleet sidebar and session grid.

```
+--------------------------------------------------------------------------+
|  Claudony                                             [+ New Session]     |
+--------------+--------------------------------+--------------------------+
|  FLEET       |  SESSIONS                      |  MESH       [O][#][=]<  |
|              |                                |                          |
|  * peer-1    |  [session-a]  [session-b]      |  <active view>           |
|  * peer-2    |  [session-c]                   |                          |
|              |                                |                          |
+--------------+--------------------------------+--------------------------+
```

The panel is **300px fixed width**, collapsible. Collapse animates via CSS
`transition: width 0.2s`. Collapse/expand state persisted to `localStorage`.
When collapsed, a slim expand button appears at the right edge as a re-open affordance.

---

## View System

The panel header contains three icon buttons that switch the active view. Active
button is highlighted in teal. View selection persisted to `localStorage`.

| Icon | View | Purpose |
|---|---|---|
| Overview | Overview | Presence strip + channel list + inline messages for selected channel (defaults to most recently active channel) |
| Channel | Channel | Full message thread for one channel (dropdown to switch); fetches `/channels/{name}/timeline` on channel switch and on each poll cycle |
| Feed | Feed | Chronological stream of all messages across all channels |

This is an **extensible view system** — new views can be added by registering a new
renderer object and adding a button. The panel infrastructure does not care how many
views exist.

---

## Data and Refresh Strategy

The dashboard fetches Qhorus data from new Claudony REST endpoints
(`/api/mesh/*`). The refresh approach is **configurable** via `application.properties`:

```properties
claudony.mesh.refresh-strategy=poll   # poll | sse
claudony.mesh.refresh-interval=3000   # ms (polling mode only)
```

Two strategy implementations, same interface (`start()` / `stop()`):

- **PollingMeshStrategy** — `setInterval` calling `/api/mesh/channels`,
  `/api/mesh/instances`, `/api/mesh/feed` in parallel. Consistent with existing
  dashboard polling pattern.
- **SseMeshStrategy** — `EventSource` subscribing to `/api/mesh/events`. Server
  pushes `mesh-update` events with a full snapshot whenever Qhorus data changes.

The frontend reads `/api/mesh/config` at startup to determine which strategy to wire up.

Default is `poll` at 3000 ms. `sse` is available for deployments where lower
latency matters.

---

## Persistence and DB Abstraction

**Qhorus owns all persistence.** `MeshResource` is read-only and never touches the
database directly.

Qhorus uses **Hibernate ORM with Panache** (entities: `Channel`, `Message`,
`Instance`, `Capability`, `AgentMessageLedgerEntry`). Schema is created via Hibernate
schema generation (`update` in dev, `drop-and-create` in test) — no Flyway.

**`QhorusMcpTools` is the read facade.** It already has N+1-safe, batched read
methods that return exactly what the dashboard needs. `MeshResource` delegates to
it rather than writing new queries:

| Dashboard need | Delegates to |
|---|---|
| Channel list | `tools.listChannels()` returns `List<ChannelDetail>` |
| Online instances | `tools.listInstances(null)` returns `List<InstanceInfo>` |
| Channel timeline | `tools.getChannelTimeline(name, null, limit)` |
| Cross-channel feed | `tools.listEvents(null, null, limit, null, null)` |

**V1 caution:** `getChannelTimeline` and `listEvents` return `List<Map<String,Object>>`
(MCP-tool-shaped). These serialise fine to JSON, but if the MCP format changes the
dashboard breaks. Acceptable for v1; a future pass would introduce typed DTOs in a
shared module.

---

## Backend

**New file:** `src/main/java/dev/claudony/server/MeshResource.java`

`@Path("/api/mesh")`, `@RolesAllowed("user")` (same auth as `SessionResource`).
Injects `QhorusMcpTools` and `ClaudonyConfig` — no query code in this class.

**Response types:** `ChannelDetail` and `InstanceInfo` are existing Qhorus types
(already Jackson-serialisable). `MeshConfig` is a local record. Timeline and feed
return `List<Map<String,Object>>` from Qhorus directly.

```java
record MeshConfig(String strategy, int interval) {}
```

**Endpoints:**

| Method | Path | Returns | Delegates to |
|---|---|---|---|
| GET | `/api/mesh/config` | `MeshConfig` | Reads `claudony.mesh.*` config |
| GET | `/api/mesh/channels` | `List<ChannelDetail>` | `tools.listChannels()` |
| GET | `/api/mesh/instances` | `List<InstanceInfo>` | `tools.listInstances(null)` |
| GET | `/api/mesh/channels/{name}/timeline` | `List<Map>` | `tools.getChannelTimeline(name, null, limit)` |
| GET | `/api/mesh/feed` | `List<Map>` | `tools.listEvents(null, null, limit, null, null)` |
| GET | `/api/mesh/events` | SSE stream | `text/event-stream`, `mesh-update` events |

`?limit` defaults: timeline=50, feed=100. Unknown channel name returns empty array (not 404).

**`ClaudonyConfig.java`** additions:
```java
@ConfigProperty(name = "claudony.mesh.refresh-strategy", defaultValue = "poll")
String meshRefreshStrategy();

@ConfigProperty(name = "claudony.mesh.refresh-interval", defaultValue = "3000")
int meshRefreshInterval();
```

**Auth:** `@RolesAllowed("user")` on the class — same as `SessionResource`.

---

## Frontend

**No changes to existing session or fleet code.** Mesh panel is fully additive.

**`index.html`** — add `<aside id="mesh-panel">` as the third child of `.app-body`,
plus a collapse expand button outside `.app-body` for the collapsed state.

**`style.css`** — mesh panel layout classes:
- `.mesh-panel` — `width: 300px`, `border-left: 1px solid var(--border)`, flex column, `transition: width 0.2s`
- `.mesh-panel.collapsed` — `width: 0; overflow: hidden; min-width: 0`
- `.mesh-header` — flex row: title + view switcher + collapse button
- `.mesh-view-btn` — icon button; `.mesh-view-btn.active` — teal background
- `.mesh-body` — `flex: 1; overflow-y: auto; padding: 12px`
- `.mesh-expand` — fixed right-edge button, visible only when panel is collapsed

**`dashboard.js`** — new classes, no modifications to existing:

```javascript
class MeshPanel {
  async init() {
    const cfg = await fetch('/api/mesh/config').then(r => r.json());
    this.strategy = cfg.strategy === 'sse'
      ? new SseMeshStrategy('/api/mesh/events', this)
      : new PollingMeshStrategy('/api/mesh', cfg.interval, this);
    this.strategy.start();
  }
  update(data) { /* stores data, calls active view renderer */ }
  switchView(name) { /* saves to localStorage, calls renderer */ }
  // collapse() and expand() toggle CSS class + localStorage
}

class PollingMeshStrategy {
  async poll() {
    const [channels, instances, feed] = await Promise.all([
      fetch('/api/mesh/channels').then(r => r.json()),
      fetch('/api/mesh/instances').then(r => r.json()),
      fetch('/api/mesh/feed?limit=100').then(r => r.json()),
    ]);
    this.panel.update({ channels, instances, feed });
  }
}

class SseMeshStrategy {
  start() {
    this.source = new EventSource(this.url);
    this.source.addEventListener('mesh-update',
      e => this.panel.update(JSON.parse(e.data)));
  }
}

// Pure render functions — no state, just DOM
const OverviewView = { render(container, data, panel) { /* ... */ } };
const ChannelView  = { render(container, data, panel) { /* ... */ } };
const FeedView     = { render(container, data, panel) { /* ... */ } };
```

**Empty states:** Each view renders a "No active channels" / "No agents online" /
"No recent activity" message when the relevant data is empty. No errors, no spinners
— just a calm empty state consistent with the existing dashboard style.

---

## Testing

**TDD throughout — tests written before implementation.**

### Backend unit tests — `MeshResourceTest.java`

`@QuarkusTest @TestSecurity(user="test", roles="user")`:

| Test | Assertion |
|---|---|
| `meshConfig_returnsStrategyAndInterval` | 200, JSON with `strategy="poll"`, `interval=3000` |
| `meshChannels_returnsEmptyList` | 200, JSON array `[]` (no Qhorus activity in test) |
| `meshInstances_returnsEmptyList` | 200, JSON array `[]` |
| `meshFeed_returnsEmptyList` | 200, JSON array `[]` |
| `meshTimeline_unknownChannel_returnsEmptyList` | 200, `[]` (not 404) |
| `meshEvents_returnsEventStreamContentType` | 200, `Content-Type: text/event-stream` |
| `meshChannels_withoutAuth_returns401` | 401 (no `@TestSecurity`) |
| `meshConfig_withoutAuth_returns401` | 401 |

### Static files test addition — `StaticFilesTest.java`

Add one assertion: served `index.html` contains `id="mesh-panel"`.

### End-to-end Playwright tests — `MeshPanelE2ETest.java`

New class, runs under `-Pe2e`. Four tests covering happy paths and critical behavior:

| Test | What it verifies |
|---|---|
| `meshPanel_visibleOnDashboard` | Panel header with "MESH" and the three view buttons is visible after page load |
| `meshPanel_collapseAndExpand` | Click collapse button — panel gone, expand button visible; click expand — panel back. State survives page reload (localStorage). |
| `meshPanel_viewSwitching_updatesActiveButton` | Click Channel button — active class moves; click Feed — Feed active; click Overview — Overview active. View persists across reload. |
| `meshPanel_emptyState_showsMessageNotError` | With no agents active, each view shows a readable empty-state string, no JS errors in console |

### Test config additions — `application.properties`

```properties
%test.claudony.mesh.refresh-strategy=poll
%test.claudony.mesh.refresh-interval=3000
```

---

## Issue Tracking

- Create a GitHub **epic** for the Mesh observation panel
- Child issue per task group: backend, frontend, E2E tests
- All commits reference the child issue: `Refs #N`

---

## File Map

```
New:
  src/main/java/dev/claudony/server/MeshResource.java
  src/test/java/dev/claudony/server/MeshResourceTest.java
  src/test/java/dev/claudony/frontend/MeshPanelE2ETest.java

Modified:
  src/main/java/dev/claudony/config/ClaudonyConfig.java
  src/main/resources/application.properties
  src/main/resources/META-INF/resources/app/index.html
  src/main/resources/META-INF/resources/app/style.css
  src/main/resources/META-INF/resources/app/dashboard.js
  src/test/java/dev/claudony/frontend/StaticFilesTest.java
```

---

## Not In Scope

- Human interjection (posting to a channel from the dashboard) — future
- Cross-fleet Qhorus aggregation — future
- Per-view Playwright tests with live message rendering — future (needs real agent activity)
- SSE Playwright tests — future (different config profile)
- Typed DTOs for timeline/feed responses — future (v1 uses Map<String,Object> from Qhorus)
