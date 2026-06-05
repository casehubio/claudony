---
id: PP-20260605-06af22
title: "terminate() must remove session from registry before killing the tmux session"
type: rule
scope: repo
applies_to: "ClaudonyReactiveWorkerProvisioner.terminate() and any caller that stops a CaseHub worker session"
severity: critical
violation_hint: "registry.remove() called AFTER tmux.killSession() — watcher can detect session gone before registry is cleared and publish a false WorkflowExecutionCompleted"
created: 2026-06-05
---

`terminate()` must call `registry.remove(workerId)` **before** `tmux.killSession()`. The registry entry is the watcher's cancellation signal: `ClaudonyWorkerExecutionManager.watch()` checks `registry.find(sessionId).isPresent()` on every poll cycle. If the registry is cleared first, the watcher exits cleanly without publishing. If tmux is killed first, the watcher detects the session gone, finds the registry entry still present, and publishes `WorkflowExecutionCompleted` — signalling the engine that the worker completed normally when it was explicitly terminated. The ordering is enforced by `InOrder` verification in `ClaudonyReactiveWorkerProvisionerTest.terminate_removesFromRegistryFirst_thenKillsSession`.
