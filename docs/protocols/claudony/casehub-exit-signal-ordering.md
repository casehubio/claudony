---
id: PP-20260607-b829e5
title: "Exit signal must be stored before WorkflowExecutionCompleted is sent, and drained after em.flush()"
type: rule
scope: repo
applies_to: "ClaudonyWorkerExecutionManager.watcherRunnable(), ClaudonyLedgerEventCapture.onCaseLifecycleEvent()"
severity: critical
refs:
  - ../../DESIGN.md
violation_hint: "Signal stored after eventBus.send() — SIGNAL_RECEIVED may process before WorkflowExecutionCompleted handler runs. Signal drained before em.flush() — engine reacts before the ledger row is visible."
garden_ref: "GE-20260607-25a3fe"
created: 2026-06-07
---

In `ClaudonyWorkerExecutionManager.watcherRunnable()`, `pendingExitSignals.put(caseId, roleName)` must be called before `eventBus.send(WORKER_EXECUTION_FINISHED, ...)`. Both are non-blocking event bus publishes; if the signal is sent first, `SignalReceivedEventHandler` may process it before `WorkflowExecutionCompletedHandler` has updated engine state. In `ClaudonyLedgerEventCapture.onCaseLifecycleEvent()`, the exit signal drain and `CaseHubRuntime.signal()` call must come after `em.persist(entry); em.flush()` — the engine's reaction to the context patch may query the ledger, and the row must be visible first.
