# Claudony Protocols

Rules specific to the Claudony CaseHub worker lifecycle and tmux session management.

| File | Rule Summary | Applies To |
|------|-------------|------------|
| [worker-exit-terminate-ordering.md](worker-exit-terminate-ordering.md) | registry.remove() before tmux.killSession() in terminate() | ClaudonyReactiveWorkerProvisioner.terminate() |
| [worker-session-creation.md](worker-session-creation.md) | CaseHub workers use createWorkerSession(), never createSession() | ClaudonyReactiveWorkerProvisioner.doProvision() |
| [casehub-exit-signal-ordering.md](casehub-exit-signal-ordering.md) | Exit signal stored before WorkflowExecutionCompleted send; drained after em.flush() | ClaudonyWorkerExecutionManager, ClaudonyLedgerEventCapture |
| [casehub-worker-exit-signal-path.md](casehub-worker-exit-signal-path.md) | Case goal must use .workers.<roleName>.exited path for auto-completion | All Claudony case definitions |
| [casehub-binding-when-guard.md](casehub-binding-when-guard.md) | Null-filter contextChange bindings must include a when: guard | All CaseHub YAML and DSL case definitions |
| [casehub-contextchange-yaml-form.md](casehub-contextchange-yaml-form.md) | Use contextChange: {} (empty map), never bare contextChange: | YAML case definition files in claudony-casehub |
| [engine-cdi-exclude-types-sync.md](engine-cdi-exclude-types-sync.md) | quarkus.arc.exclude-types must stay in sync with CasehubEnabledProfile and ResearcherCaseCasehubProfile overrides | application.properties + engine-enabled test profiles |
| [causal-context-side-channel-permanent.md](causal-context-side-channel-permanent.md) | causalContext map is the permanent bridge for causedByEntryId — never move to CaseLifecycleEvent | ClaudonyReactiveWorkerProvisioner, ClaudonyLedgerEventCapture |
| [provision-reactive-panache-event-loop.md](provision-reactive-panache-event-loop.md) | @WithSession calls must be pre-constructed on event loop, not chained after runSubscriptionOn(workerPool) | ClaudonyReactiveWorkerProvisioner.provision() |
| [casehub-runtime-signal-compat.md](casehub-runtime-signal-compat.md) | All CaseHubRuntime.signal() calls must go through CaseHubRuntimeCompat.signal() | ClaudonyReactiveWorkerProvisioner, ClaudonyLedgerEventCapture |
| [casehub-signal-handler-required.md](casehub-signal-handler-required.md) | SignalReceivedEventHandler must not be excluded from CasehubEnabledProfile | CaseEngineRoundTripTest.CasehubEnabledProfile exclude-types list |
