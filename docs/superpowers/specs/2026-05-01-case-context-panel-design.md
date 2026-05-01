# Case Context Panel — Design Spec
**Date:** 2026-05-01  
**Issue:** casehubio/claudony#77  
**Epic:** #75 — Three-panel unified dashboard

---

## Problem

When a Claudony session is a CaseHub worker, the right (channel) panel shows raw Qhorus channel messages but gives no case context: no role, no task status, no lineage of prior workers, and no automatic channel selection. A human watching the session has to know the channel name and manually select it.

---

## Scope Decision

A full IntelliJ-style tool window manager was evaluated and ruled out for this issue — no maintained vanilla-JS library matches the model, and building one custom is a separate effort. The current toggle-based panel approach is kept; layout rethink is deferred. This issue delivers the panel *content* end-to-end.

---

## Solution: Approach A — Case-aware channel panel

The existing channel panel becomes context-aware. When the session has a `caseId`:
- A **case-context header** is injected above the feed (role, status, elapsed time)
- A **lineage section** (collapsible, collapsed by default) sits below the header
- The panel **auto-selects** the case channel on open
- The existing **send dock** serves as human interjection (unchanged)

When there is no `caseId`, the panel behaves exactly as today.

---

## Backend

### New endpoint: `GET /api/sessions/{id}/lineage`

Added to `SessionResource`. Returns prior completed workers for the session's case.

```
→ 404   session not found
→ 200   []                  session has no caseId, or no completed workers yet
→ 200   [WorkerSummary, …]  ordered by completedAt ASC
```

**Response shape** (WorkerSummary serialised directly — no new DTO):
```json
[
  {
    "workerId": "researcher-abc",
    "workerName": "researcher-abc",
    "startedAt": "2026-05-01T10:00:00Z",
    "completedAt": "2026-05-01T10:08:00Z",
    "outputSummary": null,
    "ledgerEntryId": "uuid"
  }
]
```

**Implementation:** `CaseLineageQuery` is injected into `SessionResource`. The SPI is always available — `EmptyCaseLineageQuery` is the `@DefaultBean` fallback, `JpaCaseLineageQuery` activates when casehub-ledger is configured. No conditional wiring needed.

**Tests** (`SessionLineageResourceTest`, QuarkusTest):
- Session with `caseId` → 200, list (empty from `EmptyCaseLineageQuery` in tests — verifies wiring and JSON shape)
- Session without `caseId` → 200, `[]`
- Unknown session id → 404

---

## Frontend

### Panel structure when `caseId` is present

```
┌─ ch-panel-header ──────────────────────────────┐
│  [select channel ▼]                      [✕]  │
├─ ch-case-header (NEW) ─────────────────────────┤
│  code-reviewer  ● active  14m                  │
│  [▶ 2 prior workers]                           │
├─ ch-lineage (NEW, collapsible) ────────────────┤
│  researcher    10:00 → 10:08  (8m)             │
│  analyst       10:08 → 10:19  (11m)            │
├─ ch-feed (existing) ───────────────────────────┤
│  messages…                                     │
├─ ch-dock (existing) ───────────────────────────┤
│  [type ▼]  [textarea]  [Send]                  │
└────────────────────────────────────────────────┘
```

When there is no `caseId`, the panel is identical to today — no case header, no lineage section.

### JS changes (`terminal.js`)

The initial `GET /api/sessions/{id}` call (already made for the workers panel) is reused. If `session.caseId` is set, store it as `sessionCaseId` and `sessionRoleName`.

**`openPanel()`** — extended: if `sessionCaseId` is set, inject `.ch-case-header`, call `loadLineage()`, then after `loadChannels()` call `selectCaseChannel()`.

**`renderCaseHeader(session)`** — creates and inserts `.ch-case-header` containing:
- Role name (`session.roleName`, stripped of `claudony-worker-` prefix if present)
- Status dot + label (from `session.status`)
- Elapsed time (computed from `session.createdAt`, updated every 30s via `setInterval` — no extra network call)
- Lineage toggle row (`[▶ N prior workers]`)

**`loadLineage()`** — fetches `GET /api/sessions/{id}/lineage`, renders `.ch-lineage-list` of worker rows. Re-fetches every 60s (new workers may complete while the panel is open). Collapsed by default; lineage toggle row expands/collapses.

**`selectCaseChannel(caseId)`** — called after `loadChannels()`:
```
channels filtered to those whose name starts with "case-{caseId}/"
→ prefer "case-{caseId}/work"
→ else first match
→ else fall back to first channel (existing behaviour)
```

### Current task fields

`roleName` and `status` come from the session response (already fetched). There is no semantic "goal" field — that requires CaseHub engine integration not yet landed. The header shows role + status + elapsed only; goal is not displayed.

### CSS additions (`style.css`)

New classes styled to match the existing dark panel theme:
- `.ch-case-header` — container, subtle top border separating from dropdown row
- `.ch-case-role` — role name, slightly bold
- `.ch-case-status-dot` — coloured dot (active=green, idle=grey, faulted=red)
- `.ch-case-elapsed` — muted timestamp
- `.ch-lineage-toggle` — clickable row, chevron flips on expand
- `.ch-lineage` — collapsible container
- `.ch-lineage-row` — individual prior worker: name + time range + duration

No changes to `session.html` — the case header and lineage are created and inserted by JS.

### E2E tests

Added to `ChannelPanelE2ETest` (or a new `CasePanelE2ETest`):
- Case header visible when session has `caseId`; absent when session has no `caseId`
- Role name and status rendered correctly
- Lineage toggle expands and collapses the lineage section
- Channel auto-selected to `case-{caseId}/work` when present

---

## What this is not

- **Not gateway integration** — the send dock posts directly to Qhorus via `/api/mesh/channels/{name}/messages`. The `ClaudonyChannelBackend` gateway (#98, #99) is a clean retrofit after end-to-end is proven.
- **Not a tool window manager** — panel layout rethink is a separate effort, logged as a future idea.
- **Not real-time push** — polling (3s for channel feed, 60s for lineage). SSE/push retrofit deferred to #98.

---

## Files changed

| File | Change |
|------|--------|
| `claudony-app/.../SessionResource.java` | Add `GET /{id}/lineage` endpoint; inject `CaseLineageQuery` |
| `claudony-app/.../terminal.js` | Case header, lineage section, channel auto-select |
| `claudony-app/.../style.css` | New CSS classes for case header and lineage |
| `claudony-app/src/test/.../SessionLineageResourceTest.java` | New QuarkusTest — 3 test cases |
| `claudony-app/src/test/.../ChannelPanelE2ETest.java` | New E2E tests for case context |
