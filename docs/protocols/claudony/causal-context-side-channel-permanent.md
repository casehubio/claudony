---
id: PP-20260616-fc862e
title: "causalContext ConcurrentHashMap is the permanent bridge for ProvisionResult.causedByEntryId — do not move it to CaseLifecycleEvent"
type: rule
scope: repo
applies_to: "ClaudonyReactiveWorkerProvisioner, ClaudonyLedgerEventCapture — any code touching the causal ledger chain"
severity: important
refs:
  - ../../specs/2026-06-16-causedbyentryid-provision-path-design.md
violation_hint: "Adding causedByEntryId to CaseLifecycleEvent, or trying to pass it through the CDI event directly instead of the causalContext map"
garden_ref: "GE-20260428-29b30e"
created: 2026-06-16
---

`CaseLedgerEntry.causedByEntryId` cannot be threaded through `CaseLifecycleEvent` — the engine design spec (engine#389) explicitly decided shared events must not carry consumer-specific fields. The `causalContext: ConcurrentHashMap<UUID, UUID>` in `ClaudonyReactiveWorkerProvisioner` is the permanent side-channel: populated by `provision()` when `QhorusCausalLinkResolver.resolve()` returns a UUID, drained by `ClaudonyLedgerEventCapture.onCaseLifecycleEvent()` on the `WorkerStarted` event. Do not remove this map or route the UUID through any shared SPI type.
