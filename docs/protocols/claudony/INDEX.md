# Claudony Protocols

Rules specific to the Claudony CaseHub worker lifecycle and tmux session management.

| File | Rule Summary | Applies To |
|------|-------------|------------|
| [worker-exit-terminate-ordering.md](worker-exit-terminate-ordering.md) | registry.remove() before tmux.killSession() in terminate() | ClaudonyReactiveWorkerProvisioner.terminate() |
| [worker-session-creation.md](worker-session-creation.md) | CaseHub workers use createWorkerSession(), never createSession() | ClaudonyReactiveWorkerProvisioner.doProvision() |
| [casehub-exit-signal-ordering.md](casehub-exit-signal-ordering.md) | Exit signal stored before WorkflowExecutionCompleted send; drained after em.flush() | ClaudonyWorkerExecutionManager, ClaudonyLedgerEventCapture |
| [casehub-worker-exit-signal-path.md](casehub-worker-exit-signal-path.md) | Case goal must use .workers.<roleName>.exited path for auto-completion | All Claudony case definitions |
