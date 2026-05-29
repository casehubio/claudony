# 0008 — Fleet-aware channel backend registration via ChannelInitialisedEvent

Date: 2026-05-29
Status: Accepted

## Context and Problem Statement

`ClaudonyChannelBackend` must be registered in `ChannelGateway` for every case channel
on every fleet node. Three separate explicit `registerBackend()` call sites existed
(startup bootstrap, SSE panel open, channel creation) — none of which propagated
to peer nodes. As a result, messages dispatched on Node A produced no SSE push on
Node B's browser panel.

## Decision Drivers

* Fleet delivery: when a channel is created on Node A, Node B must also register its backend
* Idempotency: `ChannelInitialisedEvent` fires on every `initChannel()` call, including restarts
* Single source of truth: three competing registration sites are inconsistent and incomplete
* Qhorus design: `ChannelInitialisedEvent` is the SPI-intended mechanism for external backends

## Considered Options

* **Option A** — Single `@Observes ChannelInitialisedEvent` observer on `ClaudonyChannelBackend`
* **Option B** — Keep explicit `registerBackend()` call sites, add fleet fan-out to each
* **Option C** — Periodic re-bootstrap scheduled task (re-scan channels every N seconds)

## Decision Outcome

Chosen option: **Option A**, because it uses the Qhorus-idiomatic mechanism, collapses
three separate registration paths into one, and provides automatic startup recovery
without additional code — `ChannelGateway.onStart()` fires the event for all persisted
channels, which the observer handles automatically.

### Positive Consequences

* `bootstrapChannelBackends()` and per-SSE-open registration both removed — dead code eliminated
* Fleet propagation flows naturally: `createQhorusChannel()` calls `initChannel()` → event fires →
  observer registers locally, `CaseChannelCreatedEvent` propagates to peers via `ChannelSyncResource`
* Deregister-on-SSE-close race fixed: browser 1 disconnecting no longer removes the backend for browser 2
* New registration sites automatically inherit fleet propagation

### Negative Consequences / Tradeoffs

* Brief window between `deregisterBackend()` and `registerBackend()` in the observer — a
  concurrent `fanOut()` call may miss the backend during that instant. Benign under the
  current 500 ms polling SSE; may need revisiting for #131 (true-push delivery).
* `Event.fire()` (not `fireAsync()`) must be used at the source — `@ObservesAsync` observers
  are silently skipped with synchronous fire (see garden entry GE-20260529-baf565).
  `ChannelFleetBroadcaster` uses `@Observes` + `Thread.ofVirtual()` for async fan-out instead.

## Pros and Cons of the Options

### Option A — Single `@Observes ChannelInitialisedEvent` observer

* ✅ Qhorus-idiomatic: the SPI was designed for this use case
* ✅ Startup recovery is automatic — no separate bootstrap method needed
* ✅ Single registration path: easier to audit, no site drift
* ❌ Requires `initChannel()` to be called after `channelService.create()` (previously missing)

### Option B — Keep explicit call sites, add fleet fan-out to each

* ✅ No changes to the event model
* ❌ Three sites must each be updated for fleet propagation — error-prone
* ❌ Any new registration site silently lacks fleet propagation
* ❌ Startup bootstrap remains separate from channel creation registration

### Option C — Periodic re-bootstrap scheduled task

* ✅ Simple to implement
* ❌ Up to N seconds of latency before new channels have backends on all nodes
* ❌ No clear trigger for fleet propagation — relies entirely on polling

## Links

* Spec: `docs/superpowers/specs/2026-05-29-fleet-channel-backend-delivery-design.md`
* Qhorus `ChannelInitialisedEvent` pattern: `casehub-qhorus-api` gateway package
* Supersedes the implicit approach described in ADR-0006
* Garden: GE-20260529-baf565 (`@ObservesAsync` + `Event.fire()` silent drop)
* Protocols: PP-20260529-457e5f, PP-20260529-68c422, PP-20260529-e418f0
