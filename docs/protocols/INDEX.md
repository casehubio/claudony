# Claudony Protocols Index

Project-level standing rules for the Claudony codebase.

## Claudony — CaseHub Integration

See [claudony/INDEX.md](claudony/INDEX.md) for the full table.

| Protocol | One-liner |
|----------|-----------|
| [claudony/worker-exit-terminate-ordering.md](claudony/worker-exit-terminate-ordering.md) | registry.remove() before tmux.killSession() — watcher cancellation ordering |
| [claudony/worker-session-creation.md](claudony/worker-session-creation.md) | CaseHub workers use createWorkerSession(), not createSession() |
| [claudony/casehub-binding-when-guard.md](claudony/casehub-binding-when-guard.md) | Null-filter bindings require a when: guard to prevent re-provisioning on exit signal |
| [claudony/casehub-contextchange-yaml-form.md](claudony/casehub-contextchange-yaml-form.md) | contextChange: {} not bare contextChange: in YAML — bare form throws at startup |
