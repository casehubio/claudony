---
id: PP-20260607-248d31
title: "Claudony case definitions signal worker exit via 'workers.<roleName>.exited' context path"
type: rule
scope: repo
applies_to: "Any CaseHub case definition that auto-completes on Claudony worker exit; ClaudonyLedgerEventCapture"
severity: important
refs:
  - ../../DESIGN.md
  - casehub-exit-signal-ordering.md
violation_hint: "Case definition goal checks a path other than .workers.<roleName>.exited, or the signal path uses a different naming convention, preventing auto-completion."
created: 2026-06-07
---

When a Claudony researcher exits (tmux session detected gone), `ClaudonyLedgerEventCapture` calls `CaseHubRuntime.signal(caseId, "workers." + roleName + ".exited", true)`. This patches `context.workers.<roleName>.exited = true` using dot-notation path expansion. Any case definition that wants to auto-complete on worker exit must define a goal with condition `.workers.<roleName>.exited == true` and reference it in its completion criteria. The `workers.<roleName>` namespace is reserved for Claudony exit signals — do not use it for other context keys.
