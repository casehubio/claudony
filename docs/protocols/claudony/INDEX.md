# Claudony Protocols

Rules specific to the Claudony CaseHub worker lifecycle and tmux session management.

| File | Rule Summary | Applies To |
|------|-------------|------------|
| [worker-exit-terminate-ordering.md](worker-exit-terminate-ordering.md) | registry.remove() before tmux.killSession() in terminate() | ClaudonyReactiveWorkerProvisioner.terminate() |
| [worker-session-creation.md](worker-session-creation.md) | CaseHub workers use createWorkerSession(), never createSession() | ClaudonyReactiveWorkerProvisioner.doProvision() |
