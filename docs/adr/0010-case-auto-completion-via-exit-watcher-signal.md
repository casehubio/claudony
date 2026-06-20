# 0010 — Case Auto-Completion via Exit Watcher Signal

Date: 2026-06-07
Status: Accepted

## Context and Problem Statement

`AgentCase` (formerly `ResearcherCase`, renamed in #150) needs to reach `CaseStatus.COMPLETED` when the agent's tmux
session exits. Claudony workers are Claude Code sessions — they have no programmatic
output channel back to the case engine. A mechanism is needed to transition cases
from RUNNING to COMPLETED without changing Claude's workflow.

## Decision Drivers

* Zero changes to Claude's behavior or system prompt
* Correct ordering: case context must be updated only after the engine has recorded
  worker completion, and the ledger row must be visible before context evaluation fires
* Consistent with existing `drainCausalContext` pattern in `ClaudonyReactiveWorkerProvisioner`
* Must work through the engine's existing `CaseHubRuntime.signal()` API

## Considered Options

* **MCP tool** — Claude explicitly calls a completion tool when done
* **Workspace file** — Claude writes a result file; exit watcher reads it
* **Immediate signal from watcher thread** — watcher calls `signal()` after `eventBus.send()`
* **drainExitSignal pattern** — watcher stores pending role name; `ClaudonyLedgerEventCapture` drains it on `WorkerExecutionCompleted` and fires the signal

## Decision Outcome

Chosen option: **drainExitSignal pattern**, because it guarantees correct ordering
(signal fires after engine processes `WorkflowExecutionCompleted` and after the ledger
row is flushed), requires no changes to Claude's behavior, and reuses the established
drain pattern from `drainCausalContext`.

`CaseHubRuntime.signal(caseId, "workers.<roleName>.exited", true)` patches case context
via dot-notation path, fires `CONTEXT_CHANGED`, and triggers goal evaluation.
The YAML case definition goal `.workers.<roleName>.exited == true` satisfies the
completion criteria (e.g. `.workers.agent.exited == true` in `agent.yaml`).

### Positive Consequences

* No MCP tool, no file conventions, no Claude behavior changes
* Ordering is guaranteed: ledger row written, then signal, then context evaluation
* Pattern is consistent with `drainCausalContext` — same drain/produce contract
* Any case definition can opt in via a single YAML goal condition

### Negative Consequences / Tradeoffs

* `workers.<roleName>.exited` path convention is implicit — case definitions must know it
* Signal fires even if the case definition has no matching goal — harmless but wasteful
* Multi-worker cases needing all workers to exit require N goal conditions

## Pros and Cons of the Options

### MCP tool

* ✅ Explicit — Claude controls when the case completes
* ❌ Requires system prompt change and Claude awareness of CaseHub
* ❌ Claude may forget to call it or call it at the wrong time

### Workspace file

* ✅ Simple, no network required
* ❌ File path convention must be established and taught to Claude
* ❌ Exit watcher must parse and validate file content

### Immediate signal from watcher thread

* ✅ Simple — one extra line in the watcher
* ❌ Race condition: `SIGNAL_RECEIVED` may process before `WorkflowExecutionCompleted`
  handler updates engine state
* ❌ Ledger row may not yet be visible when context evaluation runs

### drainExitSignal pattern

* ✅ Correct ordering guaranteed by CDI async event chain
* ✅ No Claude behavior changes
* ✅ Consistent with existing `drainCausalContext` pattern
* ❌ Indirect path (watcher → map → observer → signal) harder to trace

## Links

* Protocol PP-20260607-b829e5 — exit signal ordering constraint
* Protocol PP-20260607-248d31 — `workers.<roleName>.exited` path convention
* Garden GE-20260607-25a3fe — `signal()` as direct context patch (undocumented)
* Issue #148
