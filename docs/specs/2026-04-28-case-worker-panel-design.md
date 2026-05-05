# Case Worker Panel — Design Spec
**Issue:** #76 — Left panel: CaseHub case graph and worker assignment list  
**Date:** 2026-04-28  
**Status:** Approved

---

## What We're Building

When a Claudony session was provisioned by CaseHub, the terminal page (`session.html`) shows a left panel listing the other workers in the same case — their role names, statuses, and the order they were provisioned. Clicking a worker switches the centre terminal to that session.

For standalone sessions (not provisioned by CaseHub), the panel shows a placeholder.

---

## Model Changes

### `Session` record (`claudony-core`)

Add two new optional fields after `expiryPolicy`:

```java
Optional<String> caseId    // UUID of the CaseHub case, if provisioned by CaseHub
Optional<String> roleName  // task type / role name within the case
```

`withStatus()` and `withLastActive()` propagate both fields unchanged.

All `Session` construction sites updated: `SessionResource.create()`, `ClaudonyWorkerProvisioner.provision()`, `ServerStartup.bootstrapRegistry()`, `SessionResource.rename()`.

### `SessionResponse` (`claudony-app`)

Add two nullable fields serialised via `@JsonInclude(NON_NULL)`:

```java
String caseId    // null for standalone sessions
String roleName  // null for standalone sessions
```

`SessionResponse.from()` maps from `session.caseId().orElse(null)` and `session.roleName().orElse(null)`.

---

## Backend

### `ClaudonyWorkerProvisioner.provision()`

Stamp `caseId` and `roleName` on the `Session` at creation:

```java
var session = new Session(sessionId, sessionName, defaultWorkingDir, command,
        SessionStatus.IDLE, Instant.now(), Instant.now(), Optional.empty(),
        Optional.ofNullable(context.caseId()).map(UUID::toString),
        Optional.ofNullable(roleName));
```

### `SessionRegistry.findByCaseId(String caseId)`

Returns all sessions with a matching `caseId`, sorted by `createdAt` ascending (provisioning order):

```java
public List<Session> findByCaseId(String caseId) {
    return sessions.values().stream()
            .filter(s -> s.caseId().map(caseId::equals).orElse(false))
            .sorted(Comparator.comparing(Session::createdAt))
            .toList();
}
```

### `SessionResource.list()` — `?caseId=` filter

Add an optional `@QueryParam("caseId")` parameter. When present, skip federation (case workers are always local) and return the filtered ordered list:

```java
@GET
public List<SessionResponse> list(
        @QueryParam("local") @DefaultValue("false") boolean localOnly,
        @QueryParam("caseId") String caseId) {
    if (caseId != null) {
        return registry.findByCaseId(caseId).stream()
                .map(s -> SessionResponse.from(s, config.port(), resolvedPolicy(s)))
                .toList();
    }
    // ... existing local + federation logic
}
```

---

## Frontend

### `session.html` layout

Add a "Workers" toggle button to the header (mirrors "Channels"). Add `<aside id="case-panel" class="case-panel collapsed">` as the first child of `#session-layout`. The layout becomes a three-column flex/grid: `[case-panel] [terminal-container] [channel-panel]`.

```html
<button id="workers-toggle-btn" class="compose-btn" title="Toggle workers panel">Workers</button>

<div id="session-layout">
    <aside id="case-panel" class="case-panel collapsed" aria-label="Case workers">
        <div class="case-panel-header">
            <span class="case-panel-title">Workers</span>
            <button id="case-close-btn" class="ch-close-btn" title="Close">&#10005;</button>
        </div>
        <div id="case-worker-list" class="case-worker-list"></div>
    </aside>
    <div id="terminal-container"></div>
    <aside id="channel-panel" ...>
```

### `terminal.js` — case panel logic

On init, fetch `GET /api/sessions/{sessionId}` to retrieve `caseId`. If `caseId` is present:
- Auto-expand the case panel
- Start polling `GET /api/sessions?caseId={caseId}&local=true` every 3 seconds
- Render the worker list

**Worker list rendering:**
- One row per worker, ordered by `createdAt` (server-sorted)
- Current session highlighted with an `.active-worker` class
- Status dot: green = `active`, grey = `idle`, red = `faulted`
- Display: `roleName` (e.g. "researcher", "coder") + status dot + time started

**Clicking a worker:**
1. Close existing WebSocket
2. Update `sessionId` variable
3. Reconnect via `connect()` with new session
4. Update header name badge
5. `history.replaceState` — update URL `?id=` param
6. Shift `.active-worker` highlight in panel

**No `caseId` (standalone session):**  
Panel starts collapsed; shows "No case assigned" placeholder. Not auto-expanded.

**Polling:**
Same 3-second interval pattern as the channel panel. No SSE needed.

---

## CSS

Extend `style.css` with `.case-panel`, `.case-panel.collapsed`, `.case-worker-list`, `.case-worker-row`, `.case-worker-row.active-worker`, `.worker-status-dot` (green/grey/red variants). Matches the existing dark theme and channel panel visual style.

---

## Testing

### Unit tests (`claudony-app`)
- `SessionRegistryTest` — `findByCaseId`: ordered by `createdAt`, excludes non-matching sessions, returns empty for unknown caseId
- `SessionResourceTest` — `?caseId=` filter: returns only matching local sessions, skips federation

### Integration tests (`claudony-app`)
- `ClaudonyWorkerProvisionerTest` — provisioned session has `caseId` and `roleName` set

### E2E tests (`claudony-app`, `-Pe2e`)
`CaseWorkerPanelE2ETest`:
- Standalone session: panel collapsed, placeholder visible
- CaseHub session (mocked via direct session creation with `caseId`): panel auto-expands, workers visible
- Clicking a worker: terminal reconnects, URL updates, highlight shifts
- Status badge reflects session status

---

## Acceptance Criteria Mapping

| AC | How satisfied |
|---|---|
| All active workers for the case appear in the left panel | `findByCaseId()` + 3s polling |
| Worker status shown with visual indicator | status dot, colour-coded |
| Clicking a worker updates the centre panel terminal | WS reconnect on click |
| Transition history (ordered worker sequence) visible | sorted by `createdAt` |
| Panel updates without full page reload | polling, no page navigation |
