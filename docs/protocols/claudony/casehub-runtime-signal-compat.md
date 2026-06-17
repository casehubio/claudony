---
id: PP-20260617-52285f
title: "All CaseHubRuntime.signal() calls must go through CaseHubRuntimeCompat.signal()"
type: rule
scope: repo
applies_to: "Any Claudony class that calls CaseHubRuntime.signal() — currently ClaudonyReactiveWorkerProvisioner.signalStarted() and ClaudonyLedgerEventCapture.onCaseLifecycleEvent()"
severity: critical
refs:
  - casehub/src/main/java/io/casehub/claudony/casehub/CaseHubRuntimeCompat.java
  - casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyReactiveWorkerProvisioner.java
  - casehub/src/main/java/io/casehub/claudony/casehub/ClaudonyLedgerEventCapture.java
violation_hint: "Direct call: caseHubRuntime.get().signal(caseId, key, value) — causes NoSuchMethodError when the engine SNAPSHOT return type doesn't match compiled bytecode; error is silently swallowed by catch(Throwable) but the signal never sends, leaving when-guards uncleaned and triggering provision loops"
garden_ref: "GE-20260617-0fa804"
created: 2026-06-17
---

`CaseHubRuntime.signal()` changed its return type from `void` to `CompletionStage<Void>` in the engine SNAPSHOT. Compiled bytecode targeting one version throws `NoSuchMethodError` at runtime against the other — and because `catch(Throwable)` silently absorbs the error, the signal never reaches the case engine. Without the signal, the when-guard that prevents re-provisioning is never cleared, causing dozens to hundreds of spurious provision cycles. `CaseHubRuntimeCompat.signal()` resolves the method by name via reflection and handles both return types. Remove this protocol and the helper class once the engine API stabilises on `CompletionStage<Void>` across all environments.
