# Design: Commitment Wire-up and Quality Batch

**Date:** 2026-05-22  
**Branch:** `issue-122-commitment-quality-batch`  
**Issues:** #122, #123, #128, #130, #132, #133  
**Deferred:** #124 (blocked — requires Qhorus `getFeed()` change; Qhorus actively modified)

---

## Scope

Six issues in one branch. All changes are Claudony-only (no Qhorus or engine changes).

| Issue | Area | Scale |
|-------|------|-------|
| #122 | Commitment wire-up in `postToChannel()` | S |
| #132 | Double-frame bug in `events()` SSE | XS |
| #133 | Java quality: registration atomicity, TOCTOU, logging | S |
| #128 | JS quality: 4 fixes in terminal.js | XS |
| #133-M5 | JS quality: fetch error feedback in catchUp/fullLoad | XS |
| #123 | Test coverage for `/api/mesh/feed` and `/api/mesh/events` | S |
| #130 | E2E: EventSource error → poll fallback (Playwright) | S |

---

## Area 1 — #122: Commitment wire-up in `postToChannel()`

**File:** `claudony-casehub/.../ClaudonyReactiveCaseChannelProvider.java`

`postToChannel()` currently passes `null` for `correlationId` to `messageService.send()`. When `correlationId` is null, `ReactiveMessageService` never opens a Qhorus Commitment — the obligation tracking state machine never fires for engine-dispatched COMMAND messages.

### Fix

Add a private static method `extractCorrelationId(String content)` that does a guarded Jackson parse on COMMAND and QUERY types only:

```java
@Override
public Uni<Void> postToChannel(CaseChannel channel, String from, String content, MessageType type) {
    UUID channelId = UUID.fromString(channel.id());
    String correlationId = (type == MessageType.COMMAND || type == MessageType.QUERY)
            ? extractCorrelationId(content) : null;
    return messageService.send(channelId, from, type, content, correlationId, null, null, null, null)
            .replaceWithVoid();
}

// Content-coupling workaround: postToChannel() SPI doesn't carry correlationId as a
// first-class parameter. Track claudony#135 for the SPI fix that removes this method.
private static String extractCorrelationId(String content) {
    try {
        JsonNode node = MAPPER.readTree(content);
        JsonNode cid = node.get("correlationId");
        return (cid != null && !cid.isNull()) ? cid.asText() : null;
    } catch (Exception e) {
        log.warnf("Could not parse correlationId from COMMAND/QUERY content — Commitment will not be tracked");
        return null;
    }
}

private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();
```

`ObjectMapper` is a static default instance — `claudony-casehub` has no REST layer. Failure to parse logs a WARN and delivers the message with `null` correlationId (no commitment, but no message loss).

No deadline parsing in this batch — `ReactiveMessageService.send()` doesn't accept a deadline parameter and engine#300 hasn't shipped the field in content yet.

### Deferred: SPI improvement

Filed as claudony#135 — add `correlationId` as a typed SPI parameter with a backwards-compatible default. When done, `extractCorrelationId()` can be deleted.

### Test

In `ClaudonyReactiveCaseChannelProviderTest`:
- Mock `ReactiveMessageService`, call `postToChannel()` with COMMAND type and content containing `"correlationId":"42"`, assert `send()` called with `"42"` as fifth argument.
- Second case: malformed JSON content — assert `send()` still called (with `null` correlationId), no exception propagated.

**GE-20260521-e39ad1 applies:** Do not verify commitment state via `CommitmentStore.findOpenByObligor(sender)` — sender is stored as requester, not obligor for COMMAND messages. Verify at the `send()` call boundary only.

---

## Area 2 — #132: Double-frame fix in `events()`

**File:** `claudony-app/.../MeshResource.java`, method `events()`

`events()` manually constructs SSE frames as `"data: " + json + "\n\n"` and emits them as `Multi<String>`. RESTEasy Reactive wraps each emitted String in another SSE frame, producing `data: data: {...}\n\n\n\n` on the wire. `dashboard.js`'s `JSON.parse(e.data)` throws on the double-wrapped string and the global mesh SSE never updates.

### Fix

Emit bare JSON strings and let RESTEasy handle SSE framing. Three strings in `events()` change:

```java
// Before
return "data: " + mapper.writeValueAsString(Map.of(...)) + "\n\n";
// After
return mapper.writeValueAsString(Map.of(...));

// Before (two error paths)
return "data: {}\n\n";
// After
return "{}";
```

`channelEvents()` was fixed in #101 via `serializeEntries()` — same pattern, already correct.

---

## Area 3 — #133: Java quality fixes (M1, M2, M4)

### M1 — Registration atomicity in `channelEvents()`

**File:** `claudony-app/.../MeshResource.java`

`gateway.deregisterBackend() + gateway.registerBackend()` are two non-atomic operations. Two concurrent SSE connections to the same channel can produce duplicate `human_observer` registrations. Currently harmless (500ms tick path), but will cause doubled tick delivery when #131 (ChannelEventBus-driven push) ships.

**Fix:** Per-channel lock map in `MeshResource`:

```java
private final ConcurrentHashMap<UUID, Object> channelRegistrationLocks = new ConcurrentHashMap<>();

// in channelEvents(), wrap the three-step block:
synchronized (channelRegistrationLocks.computeIfAbsent(channelId, k -> new Object())) {
    gateway.deregisterBackend(channelId, ClaudonyChannelBackend.BACKEND_ID);
    channelBackend.open(ref, Map.of());
    gateway.registerBackend(channelId, channelBackend, "human_observer");
}
```

Per-channel granularity — does not serialize unrelated channels. CDI proxy not used as lock object. Comment references #131 as the point where this lock can be removed once `ChannelGateway` guards `human_observer` duplicates natively.

### M2 — ChannelEventBus TOCTOU in `removeSubscriber()`

**File:** `claudony-app/.../ChannelEventBus.java`

Between `list.isEmpty()` and `subscribers.remove(channelName, list)`, a concurrent `subscribe()` call can add to `list`. Since `ConcurrentHashMap.remove(key, value)` uses `equals()` and `list.equals(list)` is always `true` (reflexive), the removal succeeds even when `list` now contains the new subscriber — orphaning it.

**Fix:** Replace body with `computeIfPresent` returning `null` to atomically prune:

```java
private void removeSubscriber(String channelName, MultiEmitter<Integer> emitter) {
    subscribers.computeIfPresent(channelName, (key, list) -> {
        list.remove(emitter);
        return list.isEmpty() ? null : list;
    });
}
```

`computeIfPresent` executes atomically under the map segment lock. Returning `null` removes the entry in the same atomic step. No separate lock needed.

### M4 — Silent null in `serializeEntries()`

**File:** `claudony-app/.../MeshResource.java`

`serializeEntries()` returns `null` on `JsonProcessingException` with no log, silently dropping SSE frames. Add `LOG.errorf(...)` before the `return null` for consistency with the `events()` error handling pattern.

---

## Area 4 — #128 + #133-M5: JS quality (terminal.js)

**File:** `claudony-app/.../resources/app/terminal.js`

Five targeted fixes:

**m-1 (`ts` comment):** Add `/* time of last catch-up, not last message */` inline on the `ts` field assignment in the cursor entry. Distinction matters for staleness interpretation.

**m-2 (`catchUp()` response guard):** Add `if (!r.ok) return;` as first line of the `.then(r => ...)` handler. Aligns with `pollChannel()`'s existing guard — prevents `r.json()` failing on 502/503 proxy errors with an unhandled rejection.

**m-3 (button closure references):** At stale prompt DOM-creation time, store `catchupBtn` and `reloadBtn` as closed-over variables (`chStalePromptCatchupBtn`, `chStalePromptReloadBtn`). `showStalePrompt()` assigns `.onclick` via those references rather than re-querying `getElementById` on each call.

**m-4 (`closePanel()` hygiene):** Add `hideStalePrompt()` as the first call in `closePanel()`. Without it, if the user opens a stale channel, sees the prompt, then closes the panel, the prompt is briefly visible on next reopen until `selectChannel()` hides it.

**M5 (fetch error feedback in catchUp/fullLoad):** Both functions currently swallow fetch errors silently. Add a `.catch` body that sets `chError.textContent` to a brief human-readable message (`'Catch-up failed — some messages may be missing.'`). Uses the existing `chError` element already used for post-message errors.

---

## Area 5 — #123: Test coverage for `/api/mesh/feed` and `/api/mesh/events`

**File:** `claudony-app/.../MeshResourceTest.java`

Four new tests added to the existing `MeshResourceTest` class. The existing `meshFeed_returnsEmptyList` stays.

**`meshFeed_withMessages_returnsEntriesTaggedWithChannel`**  
Create one channel via `channelStore.put()`, insert a message via `messageStore`, call `GET /api/mesh/feed`. Assert response is non-empty and each entry has a `channel` field matching the channel name.

**`meshFeed_multiChannel_returnsMergedAndSorted`**  
Two channels, one message each inserted in reverse chronological order. Assert feed contains entries from both channels sorted by `created_at` ascending.

**`meshFeed_limitTruncates`**  
One channel, insert 10 messages, call `GET /api/mesh/feed?limit=3`. Assert response size is 3.

**`meshEvents_sseFrameContainsChannelsInstancesFeedKeys`**  
Open the SSE stream via raw `HttpURLConnection` (same pattern as `meshEvents_returnsEventStreamContentType`), read one frame body, parse as JSON, assert `channels`, `instances`, and `feed` keys are all present. Validates the #132 fix end-to-end.

---

## Area 6 — #130: EventSource error → poll fallback (Playwright)

**File:** `claudony-app/.../e2e/ChannelPanelE2ETest.java`

New test `channelPanel_eventSourceError_fallsBackToPoll`:

1. `@BeforeEach` creates a test channel via `channelStore.put()`.
2. Register `page.route("**/api/mesh/channels/*/events**", route -> route.abort())` before navigating — aborts all SSE connections for any channel.
3. Navigate to session page, select the test channel. EventSource open fires into the route abort; `onerror` fires within milliseconds and schedules `pollChannel()` after `POLL_MS`.
4. Insert a message into the channel (via `messageStore` or HTTP post).
5. `page.waitForFunction()` until the message appears in `#ch-feed`, timeout `POLL_MS + 2000ms` (gives one full poll cycle plus margin).
6. Assert message is visible.

Route stays active for the test duration — EventSource cannot recover. Message appearance proves the poll fallback path is live.

**Playwright timing note:** `page.route()` must be registered before `selectChannel()` triggers the EventSource open. Order matters.

---

## Deferred

**#124 — Feed cursor `?after=<id>` support:** Requires adding `afterId` parameter to `QhorusDashboardService.getFeed()` in Qhorus. Deferred — Qhorus is actively being modified. Commented on the issue.
