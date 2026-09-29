---
id: PP-20260605-4b6c4e
title: "CaseHub workers must use createWorkerSession(), never createSession()"
type: rule
scope: repo
applies_to: "ClaudonyReactiveWorkerProvisioner.doProvision() and any code that creates a tmux session for a CaseHub worker"
severity: critical
violation_hint: "doProvision() calls tmux.createSession() — the resulting session keeps a shell alive after the worker command exits; tmux has-session returns 0 indefinitely; the watcher in ClaudonyWorkerExecutionManager never fires"
created: 2026-06-05
---

CaseHub worker sessions must be created with `TmuxService.createWorkerSession()`, not `createSession()`. The shell-based `createSession()` (tmux new-session + send-keys) keeps the shell alive after the worker command exits — `tmux has-session` returns 0 indefinitely, so `ClaudonyWorkerExecutionManager`'s exit watcher never detects session end and never publishes `WorkflowExecutionCompleted`. `createWorkerSession()` runs the command via `sh -c` so the session closes when the command exits (`remain-on-exit off` is set explicitly to override any user `~/.tmux.conf` settings). Regular (non-CaseHub) user sessions continue to use `createSession()`.

**Pool sessions (#239):** Pool sessions created via `TmuxSessionOperations` use `createWorkerSession()` with `remain-on-exit off` at creation (so exit detection works). On suspend, `remain-on-exit` is switched to `on` before the process is killed — the tmux session stays alive as a metadata container. On resume, `respawnPane()` restarts the process and `remain-on-exit` is set back to `off`. This is not a contradiction — the setting changes at suspend/resume boundaries, not at creation.
