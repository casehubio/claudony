---
id: PP-20260617-10cf10
title: "SignalReceivedEventHandler must not be excluded from CasehubEnabledProfile"
type: rule
scope: repo
applies_to: "CaseEngineRoundTripTest.CasehubEnabledProfile quarkus.arc.exclude-types list"
severity: important
refs:
  - app/src/test/java/io/casehub/claudony/CaseEngineRoundTripTest.java
  - docs/protocols/claudony/engine-cdi-exclude-types-sync.md
violation_hint: "Adding SignalReceivedEventHandler to CasehubEnabledProfile's exclude-types list — causes 'NO_HANDLERS,-1: No handlers for address casehub.signal.received', silently dropping all signal() calls and triggering provision loops (seen as 100+ ledger entries vs expected 1)"
created: 2026-06-17
---

`CasehubEnabledProfile` exercises the full provision-signal chain. `SignalReceivedEventHandler` is the Vert.x event bus listener for `casehub.signal.received` — without it, every `CaseHubRuntime.signal()` call fails with `(NO_HANDLERS,-1)`. The when-guard (`workers.researcher.started != true`) is never cleared, causing the engine to re-trigger provision on every subsequent `CONTEXT_CHANGED`, producing hundreds of spurious ledger entries. Include it even though engine#493 means it won't fire `CaseContextChangedEvent` after signals — for the round-trip test, guard clearing is all that is needed.
