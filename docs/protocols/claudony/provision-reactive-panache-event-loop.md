---
id: PP-20260616-d32bc3
title: "Reactive Panache calls in provision() must be pre-constructed on the event loop — never chained after runSubscriptionOn(workerPool)"
type: rule
scope: repo
applies_to: "ClaudonyReactiveWorkerProvisioner.provision() and any future method that combines blocking IO with reactive Panache"
severity: critical
refs:
  - ../../specs/2026-06-16-causedbyentryid-provision-path-design.md
violation_hint: "Calling a @WithSession-annotated CDI method inside a .flatMap() or .call() that follows .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())"
garden_ref: "GE-20260616-312ba1"
created: 2026-06-16
---

`SessionOperations.withSession()` (the `@WithSession` interceptor) requires a Vert.x safe (isolated) sub-context. Worker pool threads from `runSubscriptionOn(workerPool)` do not have one — calling any reactive Panache method from a `.flatMap()` or `.call()` chained after the worker pool switch will throw at runtime. The correct pattern is `Uni.combine()`: build both the blocking `Uni` (with `runSubscriptionOn`) and the reactive `Uni` (e.g. `QhorusCausalLinkResolver.resolve()`) before combining — the reactive Uni is pre-constructed on the event loop where `provision()` is invoked, capturing the correct context. See GE-20260616-312ba1 in the garden for the root cause.
