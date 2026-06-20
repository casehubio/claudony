---
id: PP-20260608-7ead30
title: "Use contextChange: {} (empty map), never bare contextChange: in YAML bindings"
type: rule
scope: repo
applies_to: "CaseHub YAML case definition files in claudony-casehub (casehub/*.yaml)"
severity: important
refs:
  - casehub/src/main/resources/casehub/agent.yaml
violation_hint: "bare contextChange: (no value) parses as YAML null → CaseDefinitionYamlMapper.convertTrigger() throws UnsupportedOperationException at startup"
garden_ref: "GE-20260608-a1daf1"
created: 2026-06-08
---

In CaseHub YAML case definitions, a `contextChange` trigger with no filter must be written as `contextChange: {}` (empty map). The bare form `contextChange:` parses as YAML null; `CaseDefinitionYamlMapper.convertTrigger()` branches on null and throws `UnsupportedOperationException: Only ContextChangeTrigger is currently supported`. The empty map form produces a non-null model object with `getFilter()` returning null, which the mapper converts to `ContextChangeTrigger(null)` — the intended null-filter trigger that fires unconditionally on any context change.
