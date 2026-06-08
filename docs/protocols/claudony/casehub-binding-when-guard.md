---
id: PP-20260608-365c92
title: "Null-filter contextChange bindings must include a when: guard"
type: rule
scope: repo
applies_to: "All CaseHub YAML case definitions and Java DSL case definitions in claudony-casehub"
severity: critical
refs:
  - casehub/src/main/resources/casehub/researcher.yaml
violation_hint: "contextChange: {} binding with no when: clause — second worker provisioned on exit signal context patch before goal transition fires"
garden_ref: "GE-20260608-1a56c3"
created: 2026-06-08
---

Any `contextChange` binding with no filter fires on every `CONTEXT_CHANGED` event — including context patches from `CaseHubRuntime.signal()`. `ChoreographyLoopControl` has no dedup: it checks only `caseStatus == RUNNING` and passes all eligible bindings through. Because goal and completion transitions are async (fired in the next Vert.x iteration), the case is still `RUNNING` when the exit signal fires, causing a second worker to be provisioned. Every null-filter binding must carry a `when:` expression that excludes the re-fire condition, e.g. `when: ".workers.<role>.exited != true"`.
