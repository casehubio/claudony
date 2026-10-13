---
layout: post
title: "Turning a poll into a push"
date: 2026-05-27
type: phase-update
entry_type: note
subtype: diary
projects: [claudony]
tags: [quarkus, sse, mutiny, vertx, reactive]
---

The dashboard channel panel had been polling Qhorus every 500ms for new messages. Not because that was a deliberate choice — it was a workaround. An earlier attempt at event-driven delivery had failed silently: SSE frames produced but never arriving at the browser. No exception, no warning. Switching to a timer had made it work, and there it stayed.

This week, working with Claude, we finally fixed it properly.

The root cause of the original failure turned out to be a Vert.x threading constraint. When `ClaudonyChannelBackend.post()` is called — triggered by the Qhorus message gateway — it's on a Vert.x I/O thread. The SSE response is owned by a different event loop thread. Vert.x's Netty channels aren't safe for cross-context writes; frames emitted from the wrong thread are silently discarded. `emitOn(Infrastructure.getDefaultWorkerPool())` shifts the delivery onto a worker thread, from which RESTEasy Reactive correctly marshals the write to the owning context.

That's one problem. The second was subtler.

The natural approach to combining a push stream with a heartbeat is `Multi.createBy().merging().streams(push, heartbeat)`, each with its own `transformToUniAndConcatenate`. The trouble: `transformToUniAndConcatenate` serialises within each stream, not across the merge. If both a push tick and a heartbeat tick arrive close together, both pipelines independently call `getTimeline(after: lastSentId.get())` with the same cursor value before either updates it. Both get the same messages. Both send the same frame. Duplicate delivery, no error.

The fix is to merge raw signals first — before any DB fetch — and run a single `transformToUniAndConcatenate` across the merged stream:

```java
record Signal(boolean heartbeat) {}

Multi<Signal> signals = Multi.createBy().merging().streams(
    channelEventBus.subscribe(channelName)
        .emitOn(Infrastructure.getDefaultWorkerPool())
        .map(t -> new Signal(false)),
    Multi.createFrom().ticks().every(Duration.ofSeconds(heartbeatSeconds))
        .map(t -> new Signal(true))
);
```

One serialised fetch pipeline. A push tick queued during a heartbeat's `getTimeline()` runs after, with the updated cursor.

The design went through a few rounds. I'd originally designed the heartbeat as an SSE comment — a `: keepalive\n\n` line invisible to `EventSource.onmessage`, no data sent to the browser. That requires changing the return type from `Multi<String>` to `Multi<OutboundSseEvent>`, with `@Context Sse` injection and new API surface.

Claude caught this in review: emit `"[]"` instead. An empty JSON array is a no-op in the frontend — the handler checks `entries.length` before doing anything — and the heartbeat calls `getTimeline()`, which recovers any messages that slipped through between gateway backend registration and ChannelEventBus subscription. An SSE comment would keep the connection alive but silently lose those messages.

`Multi<String>` unchanged, no new API surface, and the heartbeat does double duty.

The interval is now a platform `PreferenceKey` defaulting to 30 seconds. The frontend reads it from `/api/mesh/config`. Thirty seconds keeps connections alive through all the common proxy idle-timeout thresholds while adding only 2 DB reads per minute per idle channel — against the 120 it was doing before.
