# Human Interjection in the Mesh Panel — Design Spec

**Goal:** Allow the human operator to post messages into any Qhorus channel
directly from the Claudony dashboard Mesh panel, making the human a first-class
participant in agent coordination alongside Claude workers.

**Status:** Implemented — 2026-04-19

**Builds on:** `docs/superpowers/specs/2026-04-17-mesh-observation-panel-design.md`
(Mesh panel is fully implemented and read-only; this spec adds write capability.)

**Scope:** All three Mesh views (Overview, Channel, Feed) get access to a shared
interjection dock. Sender is fixed as `"human"`. Message type is user-selectable.
No reply threading, no artefact attachment, no target addressing — v1.

---

## Layout

The interjection dock is a fixed strip pinned to the bottom of the Mesh panel,
below the scrollable `mesh-body`. It is always visible regardless of which view
is active.

```
+---------------------------+
|  MESH        [O][#][=] <- |  ← header (unchanged)
+---------------------------+
|                           |
|  <active view content>    |  ← mesh-body (flex:1, scrolls independently)
|                           |
+---------------------------+
| #channel-name ▾  status ▾ |  ← dock: channel select + type select
| [                       ] |  ← textarea (2 rows)
| [Send]    ⚠ error msg     |  ← send button + inline error
+---------------------------+
```

`mesh-body` gains `flex: 1; overflow-y: auto`. The dock is a new
`<div id="mesh-dock">` as the second child of `<aside id="mesh-panel">`.

---

## Backend

**New endpoint added to `MeshResource`:**

```
POST /api/mesh/channels/{name}/messages
Content-Type: application/json
Authorization: (existing auth cookie)

{ "content": "...", "type": "status" }
```

Calls `qhorusMcpTools.sendMessage(name, "human", type, content, null, null)`.

**Response:**
- `200 OK` — `MessageResult` JSON (`messageId`, `channelName`, `sender`, `messageType`, …)
- `400` — blank/missing `content`
- `404` — channel not found
- `409` — channel paused or ACL blocked (`IllegalStateException` from Qhorus)
- `401` — unauthenticated

**Request record:**

```java
record PostMessageRequest(
    @NotBlank String content,
    @NotBlank String type
) {}
```

**Error mapping:** `IllegalArgumentException` (channel not found) → 404;
`IllegalStateException` (paused / ACL) → 409. Both caught explicitly —
same pattern as `timeline()` in the existing `MeshResource`.

**Auth:** `@Authenticated` on the class already covers this endpoint.

---

## Frontend

### Dock wiring (`MeshPanel`)

`MeshPanel` constructs and manages the dock. The dock is rendered once at
`init()` and never re-rendered — only its select values and state are updated.

```javascript
class MeshPanel {
    // New state
    _dockChannel = null;   // currently selected channel name
    _dockType    = 'status';

    _initDock() {
        // Wire channel-select onchange → this._dockChannel
        // Wire type-select onchange → this._dockType
        // Wire textarea keydown: Enter (no Shift) → _send()
        // Wire Send button → _send()
    }

    _updateDockChannels() {
        // Called from update() after data refresh
        // Repopulates channel <select> from this.data.channels
        // Preserves _dockChannel if still present; otherwise defaults to
        // channel with the latest lastActivityAt (ISO-8601 — sorts lexicographically)
        // Disables select + Send if channels empty
    }

    selectChannel(name) {
        // Called by view renderers when a channel item is clicked
        this._dockChannel = name;
        this._updateDockChannelSelect();
    }

    async _send() {
        const content = textarea.value.trim();
        if (!content || !this._dockChannel) return;
        try {
            await fetch(`/api/mesh/channels/${encodeURIComponent(this._dockChannel)}/messages`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content, type: this._dockType }),
            });
            textarea.value = '';
            this.strategy?.triggerPoll?.();   // immediate refresh if polling
        } catch (e) {
            this._showDockError(e.message || 'Send failed');
        }
    }

    _showDockError(msg) {
        // Sets error element text, clears after 4 seconds
    }
}
```

### Channel click wiring in view renderers

`OverviewView` and `FeedView` channel items call `meshPanel.selectChannel(name)`
instead of (or in addition to) `meshPanel.switchView('channel')`.

`ChannelView` already has a channel `<select>` — clicking a channel in that view
keeps `ChannelView._selected` in sync. `ChannelView.render()` also calls
`meshPanel.selectChannel(this._selected)` after resolving the active channel so
the dock tracks it.

### Immediate poll trigger

`PollingMeshStrategy` gains a `triggerPoll()` method that cancels the current
interval timer, fires `_poll()` immediately, and restarts the interval.
`SseMeshStrategy.triggerPoll()` is a no-op (SSE is already live).

`MeshPanel._send()` calls `this.strategy?.triggerPoll?.()` on success so the
sent message appears in the timeline quickly.

---

## CSS additions

```css
#mesh-dock {
    border-top: 1px solid var(--border);
    padding: 8px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    flex-shrink: 0;
}

.mesh-dock-controls {
    display: flex;
    gap: 4px;
}

.mesh-dock-controls select {
    flex: 1;
    background: var(--bg-secondary);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 3px;
    font-size: 0.75rem;
}

.mesh-dock-textarea {
    width: 100%;
    resize: none;
    background: var(--bg-secondary);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 3px;
    padding: 4px 6px;
    font-size: 0.8rem;
    font-family: inherit;
    box-sizing: border-box;
}

.mesh-dock-footer {
    display: flex;
    align-items: center;
    gap: 6px;
}

.mesh-dock-send {
    font-size: 0.75rem;
    padding: 3px 10px;
    background: var(--accent);
    color: #000;
    border: none;
    border-radius: 3px;
    cursor: pointer;
}

.mesh-dock-send:disabled { opacity: 0.4; cursor: default; }

.mesh-dock-error {
    font-size: 0.7rem;
    color: var(--error, #e57373);
    flex: 1;
}
```

---

## HTML addition (`index.html`)

Inside `<aside id="mesh-panel">`, after `<div class="mesh-body" id="mesh-body">`:

```html
<div id="mesh-dock">
    <div class="mesh-dock-controls">
        <select id="mesh-dock-channel" disabled>
            <option>— no channels —</option>
        </select>
        <select id="mesh-dock-type">
            <option value="status">status</option>
            <option value="request">request</option>
            <option value="response">response</option>
            <option value="handoff">handoff</option>
            <option value="done">done</option>
        </select>
    </div>
    <textarea id="mesh-dock-textarea" class="mesh-dock-textarea"
        rows="2" placeholder="Type a message… (Enter to send)"></textarea>
    <div class="mesh-dock-footer">
        <button id="mesh-dock-send" class="mesh-dock-send" disabled>Send</button>
        <span id="mesh-dock-error" class="mesh-dock-error"></span>
    </div>
</div>
```

---

## Testing

### Backend — additions to `MeshResourceTest.java`

All tests use `@QuarkusTest @TestSecurity(user="test", roles="user")` unless noted.

| Test | Assertion |
|---|---|
| `postMessage_sendsToChannel` | POST valid body → 200, `MessageResult` JSON, `sender="human"` |
| `postMessage_blankContent_returns400` | POST `{"content":"","type":"status"}` → 400 |
| `postMessage_unknownChannel_returns404` | POST to non-existent channel → 404 |
| `postMessage_withoutAuth_returns401` | No `@TestSecurity` → 401 |

Paused-channel 409 is covered by Qhorus's own test suite — not duplicated here.

The `postMessage_sendsToChannel` test requires a real Qhorus channel. Use the
existing `QhorusMcpTools` CDI bean to `createChannel("test-interjection", ...)`
in `@BeforeEach`, then clean up in `@AfterEach`.

### Frontend — additions to `MeshPanelE2ETest.java`

| Test | What it verifies |
|---|---|
| `interjectionDock_visibleInAllViews` | Dock (`#mesh-dock`) is present and visible on Overview, Channel, and Feed views |
| `interjectionDock_channelSelectUpdatesOnChannelClick` | Click a channel item in Overview → `#mesh-dock-channel` value updates to that channel name |
| `interjectionDock_disabledWhenNoChannels` | With no agents active, Send button and channel select are disabled |

Full send-and-see-message test deferred — requires live agent activity in the
test environment (same constraint as per-view rendering tests in the Phase 8 spec).

---

## File Map

```
Modified:
  src/main/java/dev/claudony/server/MeshResource.java
  src/main/resources/META-INF/resources/app/index.html
  src/main/resources/META-INF/resources/app/style.css
  src/main/resources/META-INF/resources/app/dashboard.js
  src/test/java/dev/claudony/server/MeshResourceTest.java
  src/test/java/dev/claudony/frontend/MeshPanelE2ETest.java
```

---

## Not In Scope

- Reply threading (`inReplyTo`) — future
- Artefact attachment (`artefactRefs`) — future
- Target addressing (`instance:`, `capability:`, `role:`) — future
- Sender identity beyond `"human"` (e.g. logged-in username) — future
- Cross-fleet interjection — future
- Speech act–based message type taxonomy — logged in IDEAS.md
