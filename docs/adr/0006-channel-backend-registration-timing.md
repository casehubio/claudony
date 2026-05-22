# 0006 — Channel Backend Registration Timing

Date: 2026-05-22
Status: Accepted

## Context and Problem Statement

`ClaudonyChannelBackend` must be registered with `ChannelGateway` so `fanOut()` delivers
messages to active SSE clients. The question is when to register it: eagerly when a case
channel is opened (`ClaudonyCaseChannelProvider.openChannel()`), or lazily when a browser
opens an EventSource connection (`MeshResource.channelEvents()`).

## Decision Drivers

* `claudony-app` depends on `claudony-casehub`; the reverse creates a circular Maven dependency
* There is nothing to push to until a browser has an active EventSource open
* `ServerStartup.bootstrapChannelBackends()` handles restart recovery independently of first-open timing

## Considered Options

* **Eager** — register in `openChannel()` the moment a channel is created
* **Lazy** — register in `channelEvents()` when a browser opens the EventSource
* **CDI events** — fire a `ChannelOpenedEvent` from `claudony-casehub`; observe in `claudony-app`

## Decision Outcome

Chosen option: **Lazy (at SSE subscribe time)**, because it avoids a circular module
dependency with no functional loss — there are no SSE subscribers to deliver to until
a browser opens the EventSource, so early registration provides no benefit.

### Positive Consequences

* No circular Maven dependency between `claudony-app` and `claudony-casehub`
* Registration and the SSE lifecycle are co-located in `MeshResource` — easier to reason about
* Idempotent re-registration on every connect is simple and correct

### Negative Consequences / Tradeoffs

* If an agent posts to a channel before any browser has ever connected, the backend is not
  registered and `fanOut()` is a no-op; message is only accessible via timeline polling/catch-up
* CDI event approach (Option 3) would preserve eager semantics without the circular dep,
  but adds event infrastructure for a marginal benefit

## Pros and Cons of the Options

### Eager — register in openChannel()

* ✅ Backend registered the moment a channel exists; every message is fanned out
* ❌ Requires injecting `claudony-app` beans into `claudony-casehub` — circular Maven dependency

### Lazy — register in channelEvents() (chosen)

* ✅ No circular dependency; clean module boundary
* ✅ Simple implementation — registration co-located with SSE lifecycle
* ❌ Backend not registered until first SSE connect; pre-connect messages are not pushed

### CDI events — ChannelOpenedEvent from casehub layer

* ✅ Eager semantics without circular dependency
* ❌ Adds event infrastructure and indirection for a marginal benefit

## Links

* claudony#98 — ClaudonyChannelBackend implementation
* PP-20260522-c741d7 — Module boundary protocol (claudony-casehub must not depend on claudony-app)
